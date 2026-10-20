package cn.iocoder.yudao.module.mes.service.wm.salesnotice;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesLineMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcService;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcServiceImpl;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesService;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesLineService;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesLineServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesDetailService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Native InnoDB RR locks/read views with real fulfillment, sales execution and OQC callback services.
 * JDBC-backed mapper doubles adapt ordinary/locking row reads without replacing production invariants.
 * Only the reservation-only tests use a transaction-proxied command for the final line insertion.
 * Opt in with an EMPTY sales_fulfillment_it_* schema on the disposable local test instance only.
 */
@EnabledIfEnvironmentVariable(named = "MES_SALES_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:59685/sales_fulfillment_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmSalesNoticeFulfillmentMySqlConcurrencyTest {

    private static final Set<String> TABLES = Set.of("mes_wm_sales_notice", "mes_wm_sales_notice_line",
            "mes_wm_product_sales", "mes_wm_product_sales_line", "mes_wm_product_sales_detail", "mes_wm_transaction",
            "mes_qc_oqc");
    private static final long NOTICE = 1L;
    private static final long NOTICE_LINE = 11L;
    private static final long FIRST_SALES = 101L;
    private static final long SECOND_SALES = 102L;
    private DataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private boolean ownsTables;
    private ReservationCommand command;
    private MesWmSalesNoticeFulfillmentService fulfillment;
    private MesWmProductSalesService sales;
    private MesWmProductSalesLineService lines;
    private MesWmTransactionService postings;
    private MesQcOqcService oqc;
    private MesQcIndicatorResultService results;
    private TransactionTemplate transaction;
    private final List<MesWmTransactionSaveReqDTO> posted = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<Long, MesWmProductSalesDO> afterSalesSnapshot = (id, row) -> {};
    private volatile BiConsumer<Long, MesWmProductSalesLineDO> afterLineSnapshot = (id, row) -> {};
    private final AtomicInteger salesLocks = new AtomicInteger();
    private final AtomicInteger qualityUpdates = new AtomicInteger();
    private final AtomicInteger inspectionSnapshots = new AtomicInteger();
    private final AtomicInteger inspectionCurrentReads = new AtomicInteger();
    private final AtomicInteger inspectionUpdates = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(0L);
        String url = System.getenv("MES_SALES_MYSQL_URL");
        URI location = URI.create(url.substring(5));
        assertEquals("127.0.0.1", location.getHost());
        assertEquals(59685, location.getPort(), "Only the disposable test MySQL instance is permitted");
        schema = location.getPath().substring(1);
        assertTrue(schema.matches("sales_fulfillment_it_[A-Za-z0-9_]+"));
        source = new DriverManagerDataSource(url, System.getenv("MES_SALES_MYSQL_USER"),
                System.getenv("MES_SALES_MYSQL_PASSWORD"));
        jdbc = checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema)
                .isEmpty(), "Use a dedicated EMPTY test schema; never an application database");
        ownsTables = true;
        String audit = ",tenant_id BIGINT NOT NULL DEFAULT 0,deleted BIT NOT NULL DEFAULT 0";
        jdbc.execute("CREATE TABLE mes_wm_sales_notice(id BIGINT PRIMARY KEY,client_id BIGINT,status INT"
                + audit + ") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_sales_notice_line(id BIGINT PRIMARY KEY,notice_id BIGINT,item_id BIGINT,"
                + "quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),oqc_check_flag BIT" + audit
                + ",KEY idx_notice(notice_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales(id BIGINT PRIMARY KEY,notice_id BIGINT,client_id BIGINT,"
                + "status INT" + audit + ",KEY idx_notice(notice_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_line(id BIGINT AUTO_INCREMENT PRIMARY KEY,sales_id BIGINT,"
                + "notice_line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),"
                + "oqc_check_flag BIT,oqc_id BIGINT,quality_status INT,material_stock_id BIGINT" + audit
                + ",KEY idx_sales(sales_id),KEY idx_notice_line(notice_line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,sales_id BIGINT,"
                + "line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),material_stock_id BIGINT,batch_id BIGINT,"
                + "batch_code VARCHAR(64),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT" + audit
                + ",KEY idx_sales(sales_id),KEY idx_line(line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type INT,biz_id BIGINT,"
                + "biz_line_id BIGINT,item_id BIGINT,batch_id BIGINT,batch_code VARCHAR(64),quantity DECIMAL(20,6)"
                + audit + ",KEY idx_biz(biz_id),KEY idx_line(biz_line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_qc_oqc(id BIGINT PRIMARY KEY,source_doc_type INT,source_doc_id BIGINT,"
                + "source_line_id BIGINT,source_doc_code VARCHAR(64),item_id BIGINT,client_id BIGINT,"
                + "out_quantity DECIMAL(20,6),batch_code VARCHAR(64),status INT,check_result INT"
                + audit + ") ENGINE=InnoDB");

        var mapper = mock(MesWmProductSalesMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            var row = salesRow(id, false);
            afterSalesSnapshot.accept(id, row);
            return row;
        });
        when(mapper.selectForUpdate(anyLong())).thenAnswer(call -> {
            salesLocks.incrementAndGet();
            return salesRow(call.getArgument(0), true);
        });
        when(mapper.updateById(any(MesWmProductSalesDO.class))).thenAnswer(call -> {
            MesWmProductSalesDO row = call.getArgument(0);
            return jdbc.update("UPDATE mes_wm_product_sales SET status=? WHERE id=?", row.getStatus(), row.getId());
        });
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        var target = new MesWmSalesNoticeFulfillmentService();
        ReflectionTestUtils.setField(target, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(target, "productSalesMapper", mapper);
        fulfillment = proxied(target, manager);
        command = proxied(new ReservationCommand(fulfillment, jdbc), manager);
        configureDocumentServices(mapper, manager);
        seed();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (!ownsTables) return;
        var cleanup = checked();
        List<String> tables = cleanup.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=?", String.class, schema);
        assertTrue(TABLES.containsAll(tables), "Unexpected tables: refusing cleanup");
        for (String table : tables) cleanup.execute("DROP TABLE `" + table + "`");
    }

    @Test
    void twoOldSnapshotsCompetingForSixOfTenPermitOnlyOneReservation() throws Exception {
        var snapshots = new CyclicBarrier(2);
        var observed = new AtomicInteger();
        afterSalesSnapshot = (id, row) -> {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line", Integer.class));
            observed.incrementAndGet();
            await(snapshots);
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> reserve(FIRST_SALES, null));
            Future<Throwable> second = executor.submit(() -> reserve(SECOND_SALES, null));
            Throwable firstResult = first.get(25, TimeUnit.SECONDS);
            Throwable secondResult = second.get(25, TimeUnit.SECONDS);
            assertEquals(2, observed.get(), "Both RR transactions must have first observed zero reservations");
            assertEquals(1, (firstResult == null ? 1 : 0) + (secondResult == null ? 1 : 0));
            assertInstanceOf(ServiceException.class, firstResult == null ? secondResult : firstResult);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line", Integer.class));
            assertQuantity("6", jdbc.queryForObject("SELECT SUM(quantity) FROM mes_wm_product_sales_line", BigDecimal.class));
            var summary = fulfillment.summaries(NOTICE, null).get(NOTICE_LINE);
            assertTrue(summary.isFulfillmentReliable());
            assertQuantity("0", summary.getShippedQuantity());
            assertQuantity("6", summary.getReservedQuantity());
            assertQuantity("4", summary.getAvailableQuantity());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oldSnapshotWaitingOnNoticeLockStillCountsTheWinnersCommittedReservation() throws Exception {
        var winnerInserted = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var loserSnapshot = new CountDownLatch(1);
        var loserConnection = new AtomicLong();
        var oldViews = new AtomicInteger();
        afterSalesSnapshot = (id, row) -> {
            if (id != SECOND_SALES) return;
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line", Integer.class),
                    "The loser's established RR view cannot yet see the uncommitted six");
            oldViews.incrementAndGet();
            loserConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            loserSnapshot.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> winner = executor.submit(() -> reserve(FIRST_SALES, () -> {
                winnerInserted.countDown();
                await(releaseWinner);
            }));
            assertTrue(winnerInserted.await(10, TimeUnit.SECONDS));
            Future<Throwable> loser = executor.submit(() -> reserve(SECOND_SALES, null));
            assertTrue(loserSnapshot.await(10, TimeUnit.SECONDS));
            awaitNoticeLockWait(loserConnection.get());
            assertFalse(loser.isDone(), "The notice mutex must block the second transaction before its sales-row lock");
            assertEquals(1, salesLocks.get(), "Only the winner may have reached a sales-row lock while notice is held");
            releaseWinner.countDown();
            assertNull(winner.get(25, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, loser.get(25, TimeUnit.SECONDS));
            assertEquals(1, oldViews.get());
            assertQuantity("6", jdbc.queryForObject("SELECT SUM(quantity) FROM mes_wm_product_sales_line", BigDecimal.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line WHERE sales_id=?",
                    Integer.class, SECOND_SALES), "Rejected reservation must not insert any partial line");
            assertQuantity("4", fulfillment.summaries(NOTICE, null).get(NOTICE_LINE).getAvailableQuantity());
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void refreshingCompletionReadsNewlyPostedEvidenceInsteadOfItsEarlierRepeatableReadSnapshot() {
        jdbc.update("UPDATE mes_wm_product_sales SET status=4 WHERE id=?", FIRST_SALES);
        insertLine(201L, FIRST_SALES, "4");
        insertPosting(301L, FIRST_SALES, 201L, "-4");

        command.refreshUsingOldSnapshot(() -> {
            // A new JdbcTemplate(source) would reuse Spring's thread-bound transaction connection.
            // Acquire a raw second connection and give it a separate DataSource identity instead.
            try (var connection = source.getConnection()) {
                assertTrue(connection.getAutoCommit(), "The independent writes must actually commit");
                var external = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertNotEquals(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class),
                        external.queryForObject("SELECT CONNECTION_ID()", Long.class),
                        "Snapshot and writer must use distinct native MySQL sessions");
                external.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,notice_line_id,item_id,quantity,oqc_check_flag)"
                        + " VALUES(202,102,11,7,6,0)");
                external.update("INSERT INTO mes_wm_transaction(id,biz_type,biz_id,biz_line_id,item_id,quantity)"
                        + " VALUES(302,118,102,202,7,-6)");
                external.update("UPDATE mes_wm_product_sales SET status=4 WHERE id=102");
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        });

        assertEquals(4, jdbc.queryForObject("SELECT status FROM mes_wm_sales_notice WHERE id=1", Integer.class));
        var summary = fulfillment.summaries(NOTICE, null).get(NOTICE_LINE);
        assertTrue(summary.isFulfillmentReliable());
        assertQuantity("10", summary.getShippedQuantity());
        assertQuantity("0", summary.getReservedQuantity());
        assertQuantity("0", summary.getAvailableQuantity());
    }

    @Test
    void actualFinishWaitingOnNoticePostsTheCurrentVerifiedQuantityAndStockIdentity() throws Exception {
        jdbc.update("UPDATE mes_wm_product_sales SET status=3 WHERE id=101");
        insertLine(201L, FIRST_SALES, "10");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,material_stock_id,"
                + "warehouse_id,location_id,area_id) VALUES(401,101,201,7,10,9,1,2,3)");
        var writerReady = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var readerReady = new CountDownLatch(1);
        var readerConnection = new AtomicLong();
        afterSalesSnapshot = (id, row) -> {
            if (id != FIRST_SALES) return;
            // These ordinary reads deliberately retain the old identity/quantity while the writer is uncommitted.
            assertQuantity("10", jdbc.queryForObject("SELECT quantity FROM mes_wm_product_sales_line WHERE id=201", BigDecimal.class));
            assertQuantity("10", jdbc.queryForObject("SELECT quantity FROM mes_wm_product_sales_detail WHERE id=401", BigDecimal.class));
            assertEquals(9L, jdbc.queryForObject("SELECT material_stock_id FROM mes_wm_product_sales_detail WHERE id=401", Long.class));
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            readerReady.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> writer = executor.submit(() -> withTenant(() -> transaction.executeWithoutResult(status -> {
                fulfillment.lockNotice(NOTICE);
                jdbc.update("UPDATE mes_wm_product_sales_line SET quantity=6 WHERE id=201");
                jdbc.update("UPDATE mes_wm_product_sales_detail SET quantity=6,material_stock_id=99 WHERE id=401");
                writerReady.countDown();
                await(releaseWriter);
            })));
            assertTrue(writerReady.await(10, TimeUnit.SECONDS));
            Future<Throwable> reader = executor.submit(() -> withTenant(() -> sales.finishProductSales(FIRST_SALES)));
            assertTrue(readerReady.await(10, TimeUnit.SECONDS));
            awaitNoticeLockWait(readerConnection.get());
            assertEquals(0, salesLocks.get(), "Finish must wait at notice before locking its sales header");
            verifyNoInteractions(postings);
            releaseWriter.countDown();
            assertNull(writer.get(25, TimeUnit.SECONDS));
            assertNull(reader.get(25, TimeUnit.SECONDS));

            verify(postings, times(1)).createTransactionList(anyList());
            assertEquals(1, posted.size(), "The actual finish path must post exactly its current verified detail set");
            var dto = posted.get(0);
            assertEquals(99L, dto.getMaterialStockId(), "The old snapshot's stock id 9 must never be posted");
            assertQuantity("-6", dto.getQuantity());
            assertEquals(201L, dto.getBizLineId());
            assertEquals(FIRST_SALES, dto.getBizId());
            assertEquals(118, dto.getBizType());
            assertEquals(4, jdbc.queryForObject("SELECT status FROM mes_wm_product_sales WHERE id=101", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT status FROM mes_wm_sales_notice WHERE id=1", Integer.class),
                    "A partial six of ten shipment keeps the notice open");
            assertQuantity("-6", jdbc.queryForObject("SELECT SUM(quantity) FROM mes_wm_transaction", BigDecimal.class));
            assertQuantity("4", fulfillment.summaries(NOTICE, null).get(NOTICE_LINE).getAvailableQuantity());
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void twoOldOqcLineSnapshotsBothPassAndAdvanceTheActualHeader() throws Exception {
        prepareOqc();
        var snapshots = new CyclicBarrier(2);
        var observed = new AtomicInteger();
        afterLineSnapshot = (id, row) -> {
            assertEquals(0, row.getQualityStatus());
            observed.incrementAndGet();
            await(snapshots);
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> withTenant(() ->
                    lines.updateProductSalesLineWhenOqcFinish(201L, 601L, 1)));
            Future<Throwable> second = executor.submit(() -> withTenant(() ->
                    lines.updateProductSalesLineWhenOqcFinish(202L, 602L, 1)));
            assertNull(first.get(25, TimeUnit.SECONDS));
            assertNull(second.get(25, TimeUnit.SECONDS));
            assertEquals(2, observed.get(), "Both OQC callbacks must have read PENDING before either takes the parent mutex");
            assertEquals(2, qualityUpdates.get());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line WHERE quality_status=1 AND oqc_id IS NOT NULL", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT status FROM mes_wm_product_sales WHERE id=101", Integer.class),
                    "The second callback must see the first PASS through current reads and advance to picking");
            verifyNoInteractions(postings);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void duplicateOqcCallbacksWithOldPendingViewsCannotReplaceTheFirstInspection() throws Exception {
        prepareOqc();
        var snapshots = new CyclicBarrier(2);
        afterLineSnapshot = (id, row) -> {
            assertEquals(201L, id);
            assertEquals(0, row.getQualityStatus());
            await(snapshots);
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> withTenant(() ->
                    lines.updateProductSalesLineWhenOqcFinish(201L, 601L, 1)));
            Future<Throwable> second = executor.submit(() -> withTenant(() ->
                    lines.updateProductSalesLineWhenOqcFinish(201L, 602L, 1)));
            Throwable a = first.get(25, TimeUnit.SECONDS);
            Throwable b = second.get(25, TimeUnit.SECONDS);
            assertEquals(1, (a == null ? 1 : 0) + (b == null ? 1 : 0));
            assertInstanceOf(ServiceException.class, a == null ? b : a);
            assertEquals(1, qualityUpdates.get(), "The second callback must fail before changing the row's inspection id");
            assertEquals(a == null ? 601L : 602L, jdbc.queryForObject("SELECT oqc_id FROM mes_wm_product_sales_line WHERE id=201", Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT quality_status FROM mes_wm_product_sales_line WHERE id=202", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT status FROM mes_wm_product_sales WHERE id=101", Integer.class));
            verifyNoInteractions(postings);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mutuallyCrossLinkedNoticeLinesAreRejectedBeforeEitherTransactionLocksTheOtherSalesLine() throws Exception {
        jdbc.update("INSERT INTO mes_wm_sales_notice(id,client_id,status) VALUES(2,8,3)");
        jdbc.update("INSERT INTO mes_wm_sales_notice_line(id,notice_id,item_id,quantity,oqc_check_flag) VALUES(12,2,7,10,0)");
        jdbc.update("UPDATE mes_wm_product_sales SET notice_id=2 WHERE id=102");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,notice_line_id,item_id,quantity,oqc_check_flag)"
                + " VALUES(201,101,12,7,6,0),(202,102,11,7,6,0)");
        var ownLinesLocked = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = executor.submit(() -> withTenant(() ->
                    command.validateCrossLinkedLine(FIRST_SALES, 201L, 11L, ownLinesLocked)));
            Future<Throwable> b = executor.submit(() -> withTenant(() ->
                    command.validateCrossLinkedLine(SECOND_SALES, 202L, 12L, ownLinesLocked)));
            var first = assertInstanceOf(ServiceException.class, a.get(25, TimeUnit.SECONDS),
                    "Dirty evidence must fail as a business validation, not an InnoDB deadlock");
            var second = assertInstanceOf(ServiceException.class, b.get(25, TimeUnit.SECONDS),
                    "Both transactions must reject without locking the other notice's line");
            assertTrue(first.getMessage().contains("跨通知"));
            assertTrue(second.getMessage().contains("跨通知"));
            assertEquals(2, salesLocks.get(), "Both transactions must have locked their own distinct sales headers");
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line", Integer.class));
            assertEquals(12L, jdbc.queryForObject("SELECT notice_line_id FROM mes_wm_product_sales_line WHERE id=201", Long.class));
            assertEquals(11L, jdbc.queryForObject("SELECT notice_line_id FROM mes_wm_product_sales_line WHERE id=202", Long.class));
            verifyNoInteractions(postings);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualOqcFinishWithAnOldLegalSnapshotRejectsCurrentChangedCustomerAndItemAfterNoticeWait() throws Exception {
        prepareOqc();
        jdbc.update("INSERT INTO mes_qc_oqc(id,source_doc_type,source_doc_id,source_line_id,item_id,client_id,"
                + "out_quantity,status,check_result) VALUES(601,118,101,201,7,8,5,0,1)");
        var writerReady = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var readerReady = new CountDownLatch(1);
        var writerConnection = new AtomicLong();
        var readerConnection = new AtomicLong();
        afterSalesSnapshot = (id, row) -> {
            if (id != FIRST_SALES) return;
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            assertEquals(8L, row.getClientId(), "The old sales header must still match the old QC customer");
            assertEquals(7L, jdbc.queryForObject("SELECT item_id FROM mes_wm_product_sales_line WHERE id=201", Long.class),
                    "The old sales line must still match the old QC item before waiting");
            assertEquals(0, jdbc.queryForObject("SELECT status FROM mes_qc_oqc WHERE id=601", Integer.class));
            assertEquals(8L, jdbc.queryForObject("SELECT client_id FROM mes_qc_oqc WHERE id=601", Long.class));
            assertEquals(7L, jdbc.queryForObject("SELECT item_id FROM mes_qc_oqc WHERE id=601", Long.class));
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            readerReady.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> writer = executor.submit(() -> withTenant(() -> transaction.executeWithoutResult(status -> {
                writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                fulfillment.lockNotice(NOTICE);
                jdbc.update("UPDATE mes_wm_product_sales SET client_id=9 WHERE id=101");
                jdbc.update("UPDATE mes_wm_product_sales_line SET item_id=8 WHERE id=201");
                writerReady.countDown();
                await(releaseWriter);
            })));
            assertTrue(writerReady.await(10, TimeUnit.SECONDS));
            Future<Throwable> reader = executor.submit(() -> withTenant(() -> oqc.finishOqc(601L)));
            assertTrue(readerReady.await(10, TimeUnit.SECONDS));
            assertNotEquals(writerConnection.get(), readerConnection.get(), "Writer and old QC snapshot must be different native sessions");
            awaitNoticeLockWait(readerConnection.get());
            assertEquals(1, inspectionSnapshots.get(), "The real QC finish entry must first locate its valid old QC snapshot");
            assertEquals(0, inspectionCurrentReads.get(), "Notice must precede the current QC row lock");
            assertEquals(0, salesLocks.get(), "The reader must wait at notice before taking its sales header lock");
            releaseWriter.countDown();
            assertNull(writer.get(25, TimeUnit.SECONDS));
            var rejected = assertInstanceOf(ServiceException.class, reader.get(25, TimeUnit.SECONDS));
            assertEquals(1_040_607_008, rejected.getCode(), "Current source mismatch must reject the actual QC finish");
            assertEquals(1, inspectionCurrentReads.get(), "Source validation must use the current locked QC record");
            assertEquals(0, inspectionUpdates.get());
            assertEquals(0, qualityUpdates.get());
            assertEquals(0, jdbc.queryForObject("SELECT status FROM mes_qc_oqc WHERE id=601", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT quality_status FROM mes_wm_product_sales_line WHERE id=201", Integer.class));
            assertNull(jdbc.queryForObject("SELECT oqc_id FROM mes_wm_product_sales_line WHERE id=201", Long.class));
            assertEquals(1, jdbc.queryForObject("SELECT status FROM mes_wm_product_sales WHERE id=101", Integer.class));
            assertEquals(9L, jdbc.queryForObject("SELECT client_id FROM mes_wm_product_sales WHERE id=101", Long.class));
            assertEquals(8L, jdbc.queryForObject("SELECT item_id FROM mes_wm_product_sales_line WHERE id=201", Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction", Integer.class));
            assertTrue(posted.isEmpty());
            verifyNoInteractions(results, postings);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void prepareOqc() {
        jdbc.update("UPDATE mes_wm_product_sales SET status=1 WHERE id=101");
        jdbc.update("UPDATE mes_wm_sales_notice_line SET oqc_check_flag=1 WHERE id=11");
        insertLine(201L, FIRST_SALES, "5");
        insertLine(202L, FIRST_SALES, "5");
        jdbc.update("UPDATE mes_wm_product_sales_line SET oqc_check_flag=1,quality_status=0 WHERE sales_id=101");
    }

    private void configureDocumentServices(MesWmProductSalesMapper salesMapper, DataSourceTransactionManager manager) {
        var lineMapper = mock(MesWmProductSalesLineMapper.class);
        when(lineMapper.selectById(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            var row = lineRow(id, false);
            afterLineSnapshot.accept(id, row);
            return row;
        });
        when(lineMapper.selectByIdForUpdate(anyLong())).thenAnswer(call -> lineRow(call.getArgument(0), true));
        when(lineMapper.selectListBySalesIdForUpdate(anyLong())).thenAnswer(call -> jdbc.query(
                "SELECT * FROM mes_wm_product_sales_line WHERE sales_id=? AND tenant_id=0 AND deleted=0 ORDER BY id FOR UPDATE",
                this::lineRow, ((Number) call.getArgument(0)).longValue()));
        when(lineMapper.updateById(any(MesWmProductSalesLineDO.class))).thenAnswer(call -> {
            MesWmProductSalesLineDO row = call.getArgument(0);
            qualityUpdates.incrementAndGet();
            return jdbc.update("UPDATE mes_wm_product_sales_line SET oqc_id=?,quality_status=? WHERE id=?",
                    row.getOqcId(), row.getQualityStatus(), row.getId());
        });
        var details = mock(MesWmProductSalesDetailService.class);
        when(details.getProductSalesDetailListBySalesId(anyLong())).thenAnswer(call -> jdbc.query(
                "SELECT * FROM mes_wm_product_sales_detail WHERE sales_id=? AND tenant_id=0 AND deleted=0 ORDER BY id FOR UPDATE",
                this::detailRow, ((Number) call.getArgument(0)).longValue()));
        postings = mock(MesWmTransactionService.class);
        doAnswer(call -> {
            List<MesWmTransactionSaveReqDTO> dtos = call.getArgument(0);
            posted.addAll(dtos);
            for (var dto : dtos) jdbc.update("INSERT INTO mes_wm_transaction(biz_type,biz_id,biz_line_id,item_id,"
                            + "batch_id,batch_code,quantity) VALUES(?,?,?,?,?,?,?)", dto.getBizType(), dto.getBizId(),
                    dto.getBizLineId(), dto.getItemId(), dto.getBatchId(), dto.getBatchCode(), dto.getQuantity());
            return null;
        }).when(postings).createTransactionList(anyList());
        var salesTarget = new MesWmProductSalesServiceImpl();
        var linesTarget = new MesWmProductSalesLineServiceImpl();
        sales = proxied(salesTarget, manager);
        lines = proxied(linesTarget, manager);
        ReflectionTestUtils.setField(salesTarget, "productSalesMapper", salesMapper);
        ReflectionTestUtils.setField(salesTarget, "productSalesLineService", lines);
        ReflectionTestUtils.setField(salesTarget, "productSalesDetailService", details);
        ReflectionTestUtils.setField(salesTarget, "fulfillmentService", fulfillment);
        ReflectionTestUtils.setField(salesTarget, "wmTransactionService", postings);
        ReflectionTestUtils.setField(linesTarget, "productSalesLineMapper", lineMapper);
        ReflectionTestUtils.setField(linesTarget, "productSalesService", sales);
        ReflectionTestUtils.setField(linesTarget, "fulfillmentService", fulfillment);
        configureInspectionService(lineMapper, manager);
    }

    @SuppressWarnings("unchecked")
    private void configureInspectionService(MesWmProductSalesLineMapper lineMapper, DataSourceTransactionManager manager) {
        var mapper = mock(MesQcOqcMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call -> {
            inspectionSnapshots.incrementAndGet();
            return inspectionRow(call.getArgument(0), false);
        });
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            // Inspect only lastSql: resolving a lambda column needs MyBatis' static table cache,
            // which this JDBC-backed mapper fixture deliberately does not initialize.
            Wrapper<?> wrapper = call.getArgument(0);
            SharedString lastSql = (SharedString) ReflectionTestUtils.getField(wrapper, "lastSql");
            assertNotNull(lastSql);
            assertEquals("FOR UPDATE", lastSql.getStringValue().trim().toUpperCase(java.util.Locale.ROOT));
            inspectionCurrentReads.incrementAndGet();
            return inspectionRow(601L, true);
        });
        when(mapper.updateById(any(MesQcOqcDO.class))).thenAnswer(call -> {
            MesQcOqcDO row = call.getArgument(0);
            inspectionUpdates.incrementAndGet();
            return jdbc.update("UPDATE mes_qc_oqc SET status=?,source_doc_code=? WHERE id=?", row.getStatus(), row.getSourceDocCode(), row.getId());
        });
        results = mock(MesQcIndicatorResultService.class);
        var target = new MesQcOqcServiceImpl();
        ReflectionTestUtils.setField(target, "oqcMapper", mapper);
        ReflectionTestUtils.setField(target, "productSalesLineMapper", lineMapper);
        ReflectionTestUtils.setField(target, "productSalesLineService", lines);
        ReflectionTestUtils.setField(target, "productSalesService", sales);
        ReflectionTestUtils.setField(target, "fulfillmentService", fulfillment);
        ReflectionTestUtils.setField(target, "indicatorResultService", results);
        oqc = proxied(target, manager);
    }

    private MesQcOqcDO inspectionRow(Long id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM mes_qc_oqc WHERE id=? AND tenant_id=0 AND deleted=0"
                + (locked ? " FOR UPDATE" : ""), (row, index) -> new MesQcOqcDO()
                .setId(row.getLong("id")).setSourceDocType((Integer) row.getObject("source_doc_type"))
                .setSourceDocId((Long) row.getObject("source_doc_id")).setSourceLineId((Long) row.getObject("source_line_id"))
                .setSourceDocCode(row.getString("source_doc_code")).setItemId((Long) row.getObject("item_id"))
                .setClientId((Long) row.getObject("client_id")).setOutQuantity(row.getBigDecimal("out_quantity"))
                .setBatchCode(row.getString("batch_code")).setStatus((Integer) row.getObject("status"))
                .setCheckResult((Integer) row.getObject("check_result")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesWmProductSalesLineDO lineRow(Long id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM mes_wm_product_sales_line WHERE id=? AND tenant_id=0 AND deleted=0"
                + (locked ? " FOR UPDATE" : ""), this::lineRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesWmProductSalesLineDO lineRow(ResultSet row, int index) throws SQLException {
        return new MesWmProductSalesLineDO().setId(row.getLong("id")).setSalesId(row.getLong("sales_id"))
                .setNoticeLineId((Long) row.getObject("notice_line_id")).setItemId(row.getLong("item_id"))
                .setQuantity(row.getBigDecimal("quantity")).setBatchId((Long) row.getObject("batch_id"))
                .setBatchCode(row.getString("batch_code")).setOqcCheckFlag(row.getBoolean("oqc_check_flag"))
                .setOqcId((Long) row.getObject("oqc_id")).setQualityStatus((Integer) row.getObject("quality_status"));
    }

    private MesWmProductSalesDetailDO detailRow(ResultSet row, int index) throws SQLException {
        return new MesWmProductSalesDetailDO().setId(row.getLong("id")).setSalesId(row.getLong("sales_id"))
                .setLineId(row.getLong("line_id")).setItemId(row.getLong("item_id"))
                .setQuantity(row.getBigDecimal("quantity")).setMaterialStockId((Long) row.getObject("material_stock_id"))
                .setBatchId((Long) row.getObject("batch_id")).setBatchCode(row.getString("batch_code"))
                .setWarehouseId((Long) row.getObject("warehouse_id")).setLocationId((Long) row.getObject("location_id"))
                .setAreaId((Long) row.getObject("area_id"));
    }

    private Throwable withTenant(Runnable action) {
        TenantContextHolder.setTenantId(0L);
        try { action.run(); return null; }
        catch (Throwable failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    private Throwable reserve(long salesId, Runnable afterInsert) {
        TenantContextHolder.setTenantId(0L);
        try {
            command.reserve(salesId, new BigDecimal("6"), afterInsert);
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void seed() {
        jdbc.update("INSERT INTO mes_wm_sales_notice(id,client_id,status) VALUES(1,8,3)");
        jdbc.update("INSERT INTO mes_wm_sales_notice_line(id,notice_id,item_id,quantity,oqc_check_flag) VALUES(11,1,7,10,0)");
        jdbc.update("INSERT INTO mes_wm_product_sales(id,notice_id,client_id,status) VALUES(101,1,8,0),(102,1,8,0)");
    }

    private void insertLine(long id, long salesId, String quantity) {
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,notice_line_id,item_id,quantity,oqc_check_flag)"
                + " VALUES(?,?,11,7,?,0)", id, salesId, new BigDecimal(quantity));
    }

    private void insertPosting(long id, long salesId, long lineId, String quantity) {
        jdbc.update("INSERT INTO mes_wm_transaction(id,biz_type,biz_id,biz_line_id,item_id,quantity) VALUES(?,118,?,?,7,?)",
                id, salesId, lineId, new BigDecimal(quantity));
    }

    private MesWmProductSalesDO salesRow(Long id, boolean locked) {
        var rows = jdbc.query("SELECT id,notice_id,client_id,status FROM mes_wm_product_sales"
                + " WHERE id=? AND tenant_id=0 AND deleted=0" + (locked ? " FOR UPDATE" : ""), this::salesRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesWmProductSalesDO salesRow(ResultSet rows, int index) throws SQLException {
        return new MesWmProductSalesDO().setId(rows.getLong("id")).setNoticeId(rows.getLong("notice_id"))
                .setClientId(rows.getLong("client_id")).setStatus(rows.getInt("status"));
    }

    private JdbcTemplate checked() {
        assertTrue(schema.matches("sales_fulfillment_it_[A-Za-z0-9_]+"));
        var checked = new JdbcTemplate(source);
        assertEquals(schema, checked.queryForObject("SELECT DATABASE()", String.class), "Refusing another database");
        return checked;
    }

    private void awaitNoticeLockWait(long connectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Integer waits = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w"
                    + " JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID"
                    + " JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"
                    + " WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mes_wm_sales_notice'",
                    Integer.class, connectionId, schema);
            if (waits != null && waits > 0) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("The second connection never waited on the real InnoDB notice mutex");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void assertQuantity(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    static class ReservationCommand {
        private final MesWmSalesNoticeFulfillmentService fulfillment;
        private final JdbcTemplate jdbc;

        ReservationCommand(MesWmSalesNoticeFulfillmentService fulfillment, JdbcTemplate jdbc) {
            this.fulfillment = fulfillment;
            this.jdbc = jdbc;
        }

        @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class, timeout = 20)
        public void reserve(long salesId, BigDecimal quantity, Runnable afterInsert) {
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            var sales = fulfillment.lockSales(salesId);
            fulfillment.validateAndReserve(sales, null, NOTICE_LINE, 7L, quantity, null, false);
            jdbc.update("INSERT INTO mes_wm_product_sales_line(sales_id,notice_line_id,item_id,quantity,oqc_check_flag)"
                    + " VALUES(?,11,7,?,0)", salesId, quantity);
            if (afterInsert != null) afterInsert.run();
        }

        @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class, timeout = 20)
        public void refreshUsingOldSnapshot(Runnable anotherConnectionPostsTheRemainingSix) {
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            assertQuantity("4", jdbc.queryForObject("SELECT -SUM(quantity) FROM mes_wm_transaction", BigDecimal.class));
            anotherConnectionPostsTheRemainingSix.run();
            assertQuantity("4", jdbc.queryForObject("SELECT -SUM(quantity) FROM mes_wm_transaction", BigDecimal.class));
            fulfillment.refreshNotice(NOTICE);
        }

        @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class, timeout = 20)
        public void validateCrossLinkedLine(long salesId, long lineId, long targetNoticeLineId, CyclicBarrier ownLinesLocked) {
            var sales = fulfillment.lockSales(salesId);
            assertEquals(lineId, jdbc.queryForObject("SELECT id FROM mes_wm_product_sales_line WHERE id=? FOR UPDATE", Long.class, lineId));
            await(ownLinesLocked);
            fulfillment.validateAndReserve(sales, lineId, targetNoticeLineId, 7L, new BigDecimal("6"), null, false);
            fail("Dirty cross-notice evidence must not be accepted for reservation");
        }
    }
}
