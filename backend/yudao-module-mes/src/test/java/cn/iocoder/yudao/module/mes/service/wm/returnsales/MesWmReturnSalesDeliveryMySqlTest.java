package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.MesWmReturnSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesLineMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_RETURN_SALES_DELIVERY_REQUIRED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Native InnoDB RR proof for actual return header/line services and delivery SQL.
 * Mapper doubles perform real ordinary/current reads; production services retain all validation.
 * Opt in only with a dedicated EMPTY return_delivery_it_* schema on the disposable test server.
 */
@EnabledIfEnvironmentVariable(named = "MES_RETURN_DELIVERY_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:59685/return_delivery_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmReturnSalesDeliveryMySqlTest {

    private static final Set<String> TABLES = Set.of("mes_wm_batch", "mes_wm_product_sales",
            "mes_wm_product_sales_line", "mes_wm_product_sales_detail", "mes_wm_transaction",
            "mes_wm_return_sales", "mes_wm_return_sales_line");
    private DataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private boolean ownsTables;
    private TransactionTemplate transaction;
    private MesWmReturnSalesDeliveryService delivery;
    private MesWmReturnSalesService returns;
    private MesWmReturnSalesLineService lines;
    private MesWmReturnSalesLineMapper lineMapper;
    private volatile Runnable beforeParentLock = () -> {};
    private final AtomicInteger currentLineReads = new AtomicInteger();
    private final AtomicInteger headerUpdates = new AtomicInteger();
    private final AtomicInteger lineUpdates = new AtomicInteger();
    private final AtomicLong newLineIds = new AtomicLong(1000);

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(0L);
        String url = System.getenv("MES_RETURN_DELIVERY_MYSQL_URL");
        URI location = URI.create(url.substring(5));
        assertEquals("127.0.0.1", location.getHost());
        assertEquals(59685, location.getPort(), "Only the disposable test MySQL instance is permitted");
        schema = location.getPath().substring(1);
        assertTrue(schema.matches("return_delivery_it_[A-Za-z0-9_]+"));
        source = new DriverManagerDataSource(url, System.getenv("MES_RETURN_DELIVERY_MYSQL_USER"),
                System.getenv("MES_RETURN_DELIVERY_MYSQL_PASSWORD"));
        jdbc = checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema)
                .isEmpty(), "Use a dedicated EMPTY test schema; never an application database");
        ownsTables = true;
        String audit = ",tenant_id BIGINT NOT NULL DEFAULT 0,deleted BIT NOT NULL DEFAULT 0";
        jdbc.execute("CREATE TABLE mes_wm_batch(id BIGINT PRIMARY KEY,item_id BIGINT,client_id BIGINT,code VARCHAR(64)"
                + audit + ") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales(id BIGINT PRIMARY KEY,client_id BIGINT,status INT,"
                + "code VARCHAR(64),notice_id BIGINT" + audit + ") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_line(id BIGINT PRIMARY KEY,sales_id BIGINT,item_id BIGINT,"
                + "quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),quality_status INT,oqc_check_flag BIT"
                + audit + ",KEY idx_sales(sales_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_detail(id BIGINT PRIMARY KEY,sales_id BIGINT,line_id BIGINT,"
                + "item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),material_stock_id BIGINT,"
                + "warehouse_id BIGINT,location_id BIGINT,area_id BIGINT" + audit
                + ",KEY idx_sales(sales_id),KEY idx_line(line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,type INT,biz_type INT,biz_id BIGINT,"
                + "biz_line_id BIGINT,biz_code VARCHAR(64),item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,"
                + "batch_code VARCHAR(64),material_stock_id BIGINT,warehouse_id BIGINT,location_id BIGINT,area_id BIGINT"
                + audit + ",KEY idx_biz(biz_id),KEY idx_line(biz_line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_sales(id BIGINT PRIMARY KEY,client_id BIGINT,status INT"
                + audit + ") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_sales_line(id BIGINT PRIMARY KEY,return_id BIGINT,item_id BIGINT,"
                + "quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),rqc_check_flag BIT,quality_status INT"
                + audit + ",KEY idx_parent(return_id)) ENGINE=InnoDB");

        var items = mock(MesMdItemService.class);
        when(items.validateItemExistsAndEnable(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        when(items.validateItemExistsAndEnable(8L)).thenReturn(new MesMdItemDO().setId(8L).setBatchFlag(false));
        var batches = mock(MesWmBatchService.class);
        // Actual delivery is authoritative even when naming metadata contains a different customer.
        when(batches.validateBatchExists(9L, 7L)).thenReturn(new MesWmBatchDO().setId(9L).setItemId(7L)
                .setClientId(99L).setCode("BROKY-B9"));
        delivery = new MesWmReturnSalesDeliveryService();
        ReflectionTestUtils.setField(delivery, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(delivery, "itemService", items);
        ReflectionTestUtils.setField(delivery, "batchService", batches);

        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        configureActualServices(manager, items, batches);
        seed();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (!ownsTables) return;
        var cleanup = checked();
        var tables = cleanup.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",
                String.class, schema);
        assertTrue(TABLES.containsAll(tables), "Unexpected tables: refusing cleanup");
        for (String table : tables) cleanup.execute("DROP TABLE `" + table + "`");
    }

    @Test
    void actualCreateWaitingOnParentUsesCurrentChangedCustomerAndRejectsMissingDelivery() throws Exception {
        var writerReady = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var readerReady = new CountDownLatch(1);
        var readerConnection = new AtomicLong();
        beforeParentLock = () -> {
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            readerReady.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> inTransaction(() -> {
                jdbc.queryForObject("SELECT id FROM mes_wm_return_sales WHERE id=1 FOR UPDATE", Long.class);
                jdbc.update("UPDATE mes_wm_return_sales SET client_id=11 WHERE id=1");
                writerReady.countDown();
                await(releaseWriter);
            }));
            assertTrue(writerReady.await(10, TimeUnit.SECONDS));
            var reader = executor.submit(() -> inTransaction(() -> {
                assertRepeatableRead();
                assertEquals(10L, jdbc.queryForObject("SELECT client_id FROM mes_wm_return_sales WHERE id=1", Long.class));
                lines.createReturnSalesLine(request(null));
            }));
            assertTrue(readerReady.await(10, TimeUnit.SECONDS));
            awaitParentLockWait(readerConnection.get());
            assertFalse(reader.isDone());
            releaseWriter.countDown();
            assertNull(writer.get(25, TimeUnit.SECONDS));
            var rejected = assertInstanceOf(ServiceException.class, reader.get(25, TimeUnit.SECONDS));
            assertEquals(WM_RETURN_SALES_DELIVERY_REQUIRED.getCode(), rejected.getCode());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_sales_line", Integer.class));
            assertEquals(11L, jdbc.queryForObject("SELECT client_id FROM mes_wm_return_sales WHERE id=1", Long.class));
            assertEquals(0, lineUpdates.get());
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualLineUpdateWaitingOnOriginalParentRejectsCurrentMovedChild() throws Exception {
        var writerReady = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var readerReady = new CountDownLatch(1);
        var readerConnection = new AtomicLong();
        beforeParentLock = () -> {
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            readerReady.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> inTransaction(() -> {
                jdbc.queryForObject("SELECT id FROM mes_wm_return_sales WHERE id=1 FOR UPDATE", Long.class);
                jdbc.update("UPDATE mes_wm_return_sales_line SET return_id=2 WHERE id=31");
                writerReady.countDown();
                await(releaseWriter);
            }));
            assertTrue(writerReady.await(10, TimeUnit.SECONDS));
            var reader = executor.submit(() -> inTransaction(() -> {
                assertRepeatableRead();
                assertEquals(1L, jdbc.queryForObject("SELECT return_id FROM mes_wm_return_sales_line WHERE id=31", Long.class));
                lines.updateReturnSalesLine(request(31L));
            }));
            assertTrue(readerReady.await(10, TimeUnit.SECONDS));
            awaitParentLockWait(readerConnection.get());
            assertEquals(0, currentLineReads.get(), "The original parent must precede a child current read");
            releaseWriter.countDown();
            assertNull(writer.get(25, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, reader.get(25, TimeUnit.SECONDS));
            assertEquals(1, currentLineReads.get());
            assertEquals(0, lineUpdates.get());
            assertEquals(2L, jdbc.queryForObject("SELECT return_id FROM mes_wm_return_sales_line WHERE id=31", Long.class));
            assertEquals(0, new BigDecimal("1").compareTo(jdbc.queryForObject(
                    "SELECT quantity FROM mes_wm_return_sales_line WHERE id=31", BigDecimal.class)));
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualSubmitWaitingOnParentRevalidatesCurrentChildInsteadOfItsOldLegalSnapshot() throws Exception {
        var writerReady = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var readerReady = new CountDownLatch(1);
        var readerConnection = new AtomicLong();
        beforeParentLock = () -> {
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            readerReady.countDown();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> inTransaction(() -> {
                jdbc.queryForObject("SELECT id FROM mes_wm_return_sales WHERE id=1 FOR UPDATE", Long.class);
                jdbc.update("UPDATE mes_wm_return_sales_line SET item_id=8,batch_id=NULL,batch_code=NULL WHERE id=31");
                writerReady.countDown();
                await(releaseWriter);
            }));
            assertTrue(writerReady.await(10, TimeUnit.SECONDS));
            var reader = executor.submit(() -> inTransaction(() -> {
                assertRepeatableRead();
                assertEquals(7L, jdbc.queryForObject("SELECT item_id FROM mes_wm_return_sales_line WHERE id=31", Long.class));
                returns.submitReturnSales(1L);
            }));
            assertTrue(readerReady.await(10, TimeUnit.SECONDS));
            awaitParentLockWait(readerConnection.get());
            releaseWriter.countDown();
            assertNull(writer.get(25, TimeUnit.SECONDS));
            var rejected = assertInstanceOf(ServiceException.class, reader.get(25, TimeUnit.SECONDS));
            assertEquals(WM_RETURN_SALES_DELIVERY_REQUIRED.getCode(), rejected.getCode());
            assertEquals(0, headerUpdates.get());
            assertEquals(0, jdbc.queryForObject("SELECT status FROM mes_wm_return_sales WHERE id=1", Integer.class));
            assertEquals(8L, jdbc.queryForObject("SELECT item_id FROM mes_wm_return_sales_line WHERE id=31", Long.class));
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void validatedDeliveryRetainsNoHistoricalSalesDetailOrLedgerRowLocks() throws Exception {
        var validated = new CountDownLatch(1);
        var releaseValidator = new CountDownLatch(1);
        var validatorConnection = new AtomicLong();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var validator = executor.submit(() -> inTransaction(() -> {
                assertRepeatableRead();
                validatorConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                delivery.validateDelivery(10L, 7L, 9L);
                validated.countDown();
                await(releaseValidator);
            }));
            assertTrue(validated.await(10, TimeUnit.SECONDS), "Delivery must have been validated before probing its locks");
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                var probe = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertNotEquals(validatorConnection.get(), probe.queryForObject("SELECT CONNECTION_ID()", Long.class));
                probe.execute("SET SESSION innodb_lock_wait_timeout=1");
                assertEquals(20L, probe.queryForObject("SELECT id FROM mes_wm_product_sales WHERE id=20 FOR UPDATE", Long.class));
                assertEquals(21L, probe.queryForObject("SELECT id FROM mes_wm_product_sales_line WHERE id=21 FOR UPDATE", Long.class));
                assertEquals(22L, probe.queryForObject("SELECT id FROM mes_wm_product_sales_detail WHERE id=22 FOR UPDATE", Long.class));
                assertEquals(23L, probe.queryForObject("SELECT id FROM mes_wm_transaction WHERE id=23 FOR UPDATE", Long.class));
                assertFalse(validator.isDone(), "The successful probes must occur before the validator commits");
                connection.rollback();
            }
            releaseValidator.countDown();
            assertNull(validator.get(25, TimeUnit.SECONDS));
        } finally {
            releaseValidator.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oldRepeatableReadViewConservativelyRejectsDeliveryThatCompletedAfterItsSnapshot() throws Exception {
        jdbc.update("UPDATE mes_wm_product_sales SET status=3 WHERE id=20");
        assertNull(inTransaction(() -> {
            assertRepeatableRead();
            assertEquals(3, jdbc.queryForObject("SELECT status FROM mes_wm_product_sales WHERE id=20", Integer.class));
            try (var connection = source.getConnection()) {
                assertTrue(connection.getAutoCommit());
                var writer = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertNotEquals(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class),
                        writer.queryForObject("SELECT CONNECTION_ID()", Long.class));
                writer.update("UPDATE mes_wm_product_sales SET status=4 WHERE id=20");
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
            var rejected = assertThrows(ServiceException.class, () -> delivery.validateDelivery(10L, 7L, 9L));
            assertEquals(WM_RETURN_SALES_DELIVERY_REQUIRED.getCode(), rejected.getCode());
        }));
        TenantContextHolder.setTenantId(0L);
        assertDoesNotThrow(() -> delivery.validateDelivery(10L, 7L, 9L),
                "A fresh transaction may accept the now-completed immutable evidence");
    }

    private void configureActualServices(DataSourceTransactionManager manager, MesMdItemService items,
                                         MesWmBatchService batches) {
        var headerMapper = mock(MesWmReturnSalesMapper.class);
        when(headerMapper.selectById(anyLong())).thenAnswer(call -> headerRow(call.getArgument(0), false));
        when(headerMapper.selectForUpdate(anyLong())).thenAnswer(call -> {
            beforeParentLock.run();
            return headerRow(call.getArgument(0), true);
        });
        when(headerMapper.updateById(any(MesWmReturnSalesDO.class))).thenAnswer(call -> {
            MesWmReturnSalesDO row = call.getArgument(0);
            headerUpdates.incrementAndGet();
            return jdbc.update("UPDATE mes_wm_return_sales SET status=? WHERE id=?", row.getStatus(), row.getId());
        });
        lineMapper = mock(MesWmReturnSalesLineMapper.class);
        when(lineMapper.selectById(anyLong())).thenAnswer(call -> lineRow(call.getArgument(0), false));
        when(lineMapper.selectByIdForUpdate(anyLong())).thenAnswer(call -> {
            currentLineReads.incrementAndGet();
            return lineRow(call.getArgument(0), true);
        });
        when(lineMapper.selectListByReturnIdForUpdate(anyLong())).thenAnswer(call -> jdbc.query(
                "SELECT * FROM mes_wm_return_sales_line WHERE return_id=? AND tenant_id=0 AND deleted=0 ORDER BY id FOR UPDATE",
                this::lineRow, ((Number) call.getArgument(0)).longValue()));
        when(lineMapper.insert(any(MesWmReturnSalesLineDO.class))).thenAnswer(call -> {
            MesWmReturnSalesLineDO row = call.getArgument(0);
            row.setId(newLineIds.incrementAndGet());
            return jdbc.update("INSERT INTO mes_wm_return_sales_line(id,return_id,item_id,quantity,batch_id,batch_code,"
                            + "rqc_check_flag,quality_status) VALUES(?,?,?,?,?,?,?,?)", row.getId(), row.getReturnId(),
                    row.getItemId(), row.getQuantity(), row.getBatchId(), row.getBatchCode(), row.getRqcCheckFlag(),
                    row.getQualityStatus());
        });
        when(lineMapper.updateById(any(MesWmReturnSalesLineDO.class))).thenAnswer(call -> {
            MesWmReturnSalesLineDO row = call.getArgument(0);
            lineUpdates.incrementAndGet();
            return jdbc.update("UPDATE mes_wm_return_sales_line SET item_id=?,quantity=?,batch_id=?,batch_code=? WHERE id=?",
                    row.getItemId(), row.getQuantity(), row.getBatchId(), row.getBatchCode(), row.getId());
        });
        var headerTarget = new MesWmReturnSalesServiceImpl();
        var lineTarget = new MesWmReturnSalesLineServiceImpl();
        returns = proxied(headerTarget, manager);
        lines = proxied(lineTarget, manager);
        ReflectionTestUtils.setField(headerTarget, "returnSalesMapper", headerMapper);
        ReflectionTestUtils.setField(headerTarget, "returnSalesLineMapper", lineMapper);
        ReflectionTestUtils.setField(headerTarget, "returnSalesLineService", lines);
        ReflectionTestUtils.setField(headerTarget, "deliveryService", delivery);
        ReflectionTestUtils.setField(headerTarget, "itemService", items);
        ReflectionTestUtils.setField(lineTarget, "returnSalesLineMapper", lineMapper);
        ReflectionTestUtils.setField(lineTarget, "returnSalesService", returns);
        ReflectionTestUtils.setField(lineTarget, "itemService", items);
        ReflectionTestUtils.setField(lineTarget, "batchService", batches);
        ReflectionTestUtils.setField(lineTarget, "deliveryService", delivery);
        ReflectionTestUtils.setField(lineTarget, "returnSalesDetailService", mock(MesWmReturnSalesDetailService.class));
var isolatedQuota = mock(MesWmReturnSalesSourceService.class);
        when(isolatedQuota.origin(anyLong(), anyLong())).thenReturn(new java.util.HashMap<>(java.util.Map.of("item_id",7L,"batch_id",9L,"batch_code","BROKY-B9")));
        when(isolatedQuota.lockDocumentSources(anyLong())).thenAnswer(call -> lineMapper.selectListByReturnIdForUpdate(call.getArgument(0)));
        doAnswer(call -> { MesWmReturnSalesDO parent=call.getArgument(0);MesWmReturnSalesLineDO line=call.getArgument(1);delivery.validateDelivery(parent.getClientId(),line.getItemId(),line.getBatchId());delivery.validateBatchCode(line.getItemId(),line.getBatchId(),line.getBatchCode());return null; }).when(isolatedQuota).validateLine(any(MesWmReturnSalesDO.class),any(MesWmReturnSalesLineDO.class));
        ReflectionTestUtils.setField(headerTarget,"sourceService",isolatedQuota);
        ReflectionTestUtils.setField(lineTarget,"sourceService",isolatedQuota);
    }

    private void seed() {
        jdbc.update("INSERT INTO mes_wm_batch(id,item_id,client_id,code) VALUES(9,7,99,'BROKY-B9')");
        jdbc.update("INSERT INTO mes_wm_product_sales(id,client_id,status,code) VALUES(20,10,4,'SALE20')");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,item_id,quantity,batch_id,batch_code,quality_status,oqc_check_flag)"
                + " VALUES(21,20,7,5,9,'BROKY-B9',1,0)");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,batch_id,batch_code,"
                + "material_stock_id,warehouse_id,location_id,area_id) VALUES(22,20,21,7,5,9,'BROKY-B9',8,2,3,4)");
        jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_line_id,biz_code,item_id,quantity,batch_id,"
                + "batch_code,material_stock_id,warehouse_id,location_id,area_id) VALUES(23,2,118,20,21,'SALE20',7,-5,9,'BROKY-B9',8,2,3,4)");
        jdbc.update("INSERT INTO mes_wm_return_sales(id,client_id,status) VALUES(1,10,0),(2,10,0)");
        jdbc.update("INSERT INTO mes_wm_return_sales_line(id,return_id,item_id,quantity,batch_id,batch_code,rqc_check_flag,quality_status)"
                + " VALUES(31,1,7,1,9,'BROKY-B9',0,1)");
    }

    private MesWmReturnSalesLineSaveReqVO request(Long id) {
        var request = new MesWmReturnSalesLineSaveReqVO();
        request.setId(id);
        request.setReturnId(1L);request.setOriginTransactionId(23L);
        request.setItemId(7L);
        request.setQuantity(new BigDecimal("2"));
        request.setBatchId(9L);
        request.setBatchCode("BROKY-B9");
        request.setRqcCheckFlag(false);
        return request;
    }

    private MesWmReturnSalesDO headerRow(Long id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM mes_wm_return_sales WHERE id=? AND tenant_id=0 AND deleted=0"
                        + (locked ? " FOR UPDATE" : ""), (row, index) -> new MesWmReturnSalesDO()
                        .setId(row.getLong("id")).setClientId(row.getLong("client_id")).setStatus(row.getInt("status")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesWmReturnSalesLineDO lineRow(Long id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM mes_wm_return_sales_line WHERE id=? AND tenant_id=0 AND deleted=0"
                + (locked ? " FOR UPDATE" : ""), this::lineRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesWmReturnSalesLineDO lineRow(ResultSet row, int index) throws SQLException {
        return new MesWmReturnSalesLineDO().setId(row.getLong("id")).setReturnId(row.getLong("return_id")).setOriginTransactionId(23L)
                .setItemId(row.getLong("item_id")).setQuantity(row.getBigDecimal("quantity"))
                .setBatchId((Long) row.getObject("batch_id")).setBatchCode(row.getString("batch_code"))
                .setRqcCheckFlag(row.getBoolean("rqc_check_flag")).setQualityStatus(row.getInt("quality_status"));
    }

    private Throwable inTransaction(Runnable action) {
        TenantContextHolder.setTenantId(0L);
        try { transaction.executeWithoutResult(status -> action.run()); return null; }
        catch (Throwable failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    private void assertRepeatableRead() {
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
    }

    private JdbcTemplate checked() {
        assertTrue(schema.matches("return_delivery_it_[A-Za-z0-9_]+"));
        var checked = new JdbcTemplate(source);
        assertEquals(schema, checked.queryForObject("SELECT DATABASE()", String.class), "Refusing another database");
        return checked;
    }

    private void awaitParentLockWait(long connectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Integer waits = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w"
                            + " JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID"
                            + " JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"
                            + " WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mes_wm_return_sales'",
                    Integer.class, connectionId, schema);
            if (waits != null && waits > 0) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("The reader never waited on the actual InnoDB return-parent lock");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
