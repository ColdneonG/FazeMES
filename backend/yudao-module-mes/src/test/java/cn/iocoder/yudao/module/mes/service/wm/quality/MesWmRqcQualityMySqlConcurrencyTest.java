package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue.MesWmReturnIssueLineMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesLineMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnIssueStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnSalesStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueLineServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesLineServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
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

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real InnoDB REPEATABLE READ snapshots/locks, real RQC service methods, JDBC-backed mapper doubles.
 * Explicit opt-in: an EMPTY mes_quality_it_* schema on the disposable 127.0.0.1:59685 instance only.
 * Does not initialize, migrate, connect to, or clean up an application database.
 */
@EnabledIfEnvironmentVariable(named = "MES_QUALITY_MYSQL_URL",
        matches = "jdbc:mysql://.+/mes_quality_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmRqcQualityMySqlConcurrencyTest {

    private static final Set<String> TABLES = Set.of("mes_wm_return_sales", "mes_wm_return_sales_line",
            "mes_wm_return_issue", "mes_wm_return_issue_line");
    private static final long PARENT = 1L;
    private static final int PENDING = MesWmQualityStatusEnum.PENDING.getStatus();
    private static final int PASS = MesWmQualityStatusEnum.PASS.getStatus();
    private static final int FAIL = MesWmQualityStatusEnum.FAIL.getStatus();
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private String schema;
    private boolean ownsSchema;

    enum Business {
        SALES("mes_wm_return_sales", "mes_wm_return_sales_line", "return_id",
                MesWmReturnSalesStatusEnum.CONFIRMED.getStatus(), MesWmReturnSalesStatusEnum.APPROVING.getStatus()),
        ISSUE("mes_wm_return_issue", "mes_wm_return_issue_line", "issue_id",
                MesWmReturnIssueStatusEnum.CONFIRMED.getStatus(), MesWmReturnIssueStatusEnum.APPROVING.getStatus());

        final String header;
        final String line;
        final String parent;
        final int confirmed;
        final int approving;

        Business(String header, String line, String parent, int confirmed, int approving) {
            this.header = header;
            this.line = line;
            this.parent = parent;
            this.confirmed = confirmed;
            this.approving = approving;
        }
    }

    @BeforeEach
    void setup() {
        String url = System.getenv("MES_QUALITY_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost(), "Only the disposable local instance is permitted");
        assertEquals(59685, uri.getPort(), "Never use the application MySQL port");
        schema = uri.getPath().substring(1);
        assertTrue(schema.matches("mes_quality_it_[A-Za-z0-9_]+"), "Only an isolated quality test schema is permitted");
        dataSource = new DriverManagerDataSource(url, System.getenv("MES_QUALITY_MYSQL_USER"),
                System.getenv("MES_QUALITY_MYSQL_PASSWORD"));
        jdbc = checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema)
                .isEmpty(), "The test schema must be EMPTY; do not reuse the migration evidence schema");
        ownsSchema = true;
        for (Business business : Business.values()) {
            jdbc.execute("CREATE TABLE " + business.header + "(id BIGINT PRIMARY KEY,status INT NOT NULL) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE " + business.line + "(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + business.parent + " BIGINT NOT NULL,item_id BIGINT,batch_id BIGINT,material_stock_id BIGINT,"
                    + "quantity DECIMAL(20,6) NOT NULL,quality_status INT NOT NULL,rqc_id BIGINT,origin_transaction_id BIGINT,batch_code VARCHAR(64),KEY idx_parent("
                    + business.parent + ")) ENGINE=InnoDB");
        }
        jdbc.execute("CREATE TABLE mes_qc_rqc(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,status INT,source_doc_type INT,source_doc_id BIGINT,source_line_id BIGINT,item_id BIGINT,batch_code VARCHAR(64),check_quantity DECIMAL(20,6),qualified_quantity DECIMAL(20,6),unqualified_quantity DECIMAL(20,6),check_result INT,deleted INT DEFAULT 0) ENGINE=InnoDB");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
    }

    @AfterEach
    void clear() {
        if (!ownsSchema) return;
        JdbcTemplate cleanup = checked();
        cleanup.execute("DROP TABLE IF EXISTS mes_qc_rqc");
        List<String> tables = cleanup.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=?", String.class, schema);
        assertTrue(TABLES.containsAll(tables), "Unexpected tables: refusing cleanup");
        for (String table : tables) cleanup.execute("DROP TABLE `" + table + "`");
    }

    @ParameterizedTest
    @EnumSource(Business.class)
    void twoOldSnapshotsStillAdvanceHeaderAfterBothDifferentLinesFinish(Business business) throws Exception {
        var fixture = fixture(business, false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> fixture.complete(11L, false, false));
            Future<Throwable> second = executor.submit(() -> fixture.complete(12L, false, false));
            assertNull(first.get(25, TimeUnit.SECONDS));
            assertNull(second.get(25, TimeUnit.SECONDS));
            assertEquals(2, fixture.pendingSnapshots.get(), "Both transactions must have read PENDING before either parent lock");
            assertEquals(business.approving, jdbc.queryForObject(
                    "SELECT status FROM " + business.header + " WHERE id=1", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM " + business.line + " WHERE quality_status=?",
                    Integer.class, PASS));
            fixture.verifyCurrentListUsed(2);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(Business.class)
    void oldSnapshotOfSameLineCannotApplyInspectionSplitTwice(Business business) throws Exception {
        var fixture = fixture(business, true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            // Both begin with the original 10/PENDING snapshot. The second lock is acquired
            // after the winner commits, while another pending line keeps the header CONFIRMED.
            Future<Throwable> winner = executor.submit(() -> fixture.complete(11L, true, false));
            Future<Throwable> loser = executor.submit(() -> fixture.complete(11L, true, true));
            assertNull(winner.get(25, TimeUnit.SECONDS));
            Throwable rejected = loser.get(25, TimeUnit.SECONDS);
            assertInstanceOf(ServiceException.class, rejected);
            assertEquals(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), ((ServiceException) rejected).getCode());
            assertEquals(2, fixture.pendingSnapshots.get());
            assertEquals(business.confirmed, jdbc.queryForObject(
                    "SELECT status FROM " + business.header + " WHERE id=1", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM " + business.line, Integer.class),
                    "Exactly one FAIL split may be inserted");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + business.line + " WHERE quality_status=?",
                    Integer.class, FAIL));
            assertEquals(0, new BigDecimal("6").compareTo(jdbc.queryForObject(
                    "SELECT quantity FROM " + business.line + " WHERE id=11", BigDecimal.class)));
            assertEquals(0, new BigDecimal("4").compareTo(jdbc.queryForObject(
                    "SELECT quantity FROM " + business.line + " WHERE quality_status=?", BigDecimal.class, FAIL)));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + business.line + " WHERE id=12 AND quality_status=?",
                    Integer.class, PENDING));
            fixture.verifyCurrentListUsed(1);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private JdbcTemplate checked() {
        JdbcTemplate connection = new JdbcTemplate(dataSource);
        assertTrue(schema.matches("mes_quality_it_[A-Za-z0-9_]+"));
        assertEquals(schema, connection.queryForObject("SELECT DATABASE()", String.class),
                "Refusing to touch a different schema");
        return connection;
    }

    private Fixture fixture(Business business, boolean serializeWinner) {
        jdbc.update("INSERT INTO " + business.header + " VALUES(1,?)", business.confirmed);
        jdbc.update("INSERT INTO " + business.line + "(id," + business.parent
                + ",item_id,batch_id,material_stock_id,quantity,quality_status,rqc_id,origin_transaction_id) VALUES"
                + "(11,1,10,7,50,10,?,111,23),(12,1,10,7,50,10,?,112,23)", PENDING, PENDING);
        return new Fixture(business, serializeWinner);
    }

    private class Fixture {
        final Business business;
        final boolean serializeWinner;
        final CyclicBarrier beforeParentLock = new CyclicBarrier(2);
        final CountDownLatch winnerCommitted = new CountDownLatch(1);
        final ThreadLocal<Boolean> waitForWinner = new ThreadLocal<>();
        final ThreadLocal<Long> inspectionLine=new ThreadLocal<>();
        final ThreadLocal<Boolean> splitInspection=new ThreadLocal<>();
        final AtomicInteger pendingSnapshots = new AtomicInteger();
        final MesWmReturnSalesLineMapper salesMapper = mock(MesWmReturnSalesLineMapper.class);
        final MesWmReturnIssueLineMapper issueMapper = mock(MesWmReturnIssueLineMapper.class);
        final MesWmReturnSalesLineServiceImpl salesService = new MesWmReturnSalesLineServiceImpl();
        final MesWmReturnIssueLineServiceImpl issueService = new MesWmReturnIssueLineServiceImpl();

        Fixture(Business business, boolean serializeWinner) {
            this.business = business;
            this.serializeWinner = serializeWinner;
            if (business == Business.SALES) configureSales(); else configureIssue();
        }

        Throwable complete(long id, boolean split, boolean second) {
            waitForWinner.set(second);
            inspectionLine.set(id);splitInspection.set(split);TenantContextHolder.setTenantId(1L);
            try {
                transaction.executeWithoutResult(status -> {
                    assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                    if (business == Business.SALES) salesService.updateReturnSalesLineWhenRqcFinish(
                            id, null, split ? 2 : 1, new BigDecimal(split ? "6" : "10"), new BigDecimal(split ? "4" : "0"));
                    else issueService.updateReturnIssueLineWhenRqcFinish(
                            id, null, split ? 2 : 1, new BigDecimal(split ? "6" : "10"), new BigDecimal(split ? "4" : "0"));
                });
                return null;
            } catch (Throwable failure) {
                return failure;
            } finally {
                if (!second) winnerCommitted.countDown(); // After TransactionTemplate has committed or rolled back.
                waitForWinner.remove();
                inspectionLine.remove();splitInspection.remove();TenantContextHolder.clear();
            }
        }

        private int lockedParentStatus() throws Exception {
            // The service's preceding non-locking line read has already established its RR view.
            beforeParentLock.await(10, TimeUnit.SECONDS);
            if (serializeWinner && Boolean.TRUE.equals(waitForWinner.get())) {
                assertTrue(winnerCommitted.await(10, TimeUnit.SECONDS));
            }
            int status=jdbc.queryForObject("SELECT status FROM " + business.header + " WHERE id=1 FOR UPDATE", Integer.class);
            // A committed, immutable inspection belongs to each source line. Set up its known fixture only once.
            boolean split=Boolean.TRUE.equals(splitInspection.get());long sourceLine=inspectionLine.get();
            jdbc.update("INSERT IGNORE INTO mes_qc_rqc(id,tenant_id,status,source_doc_type,source_doc_id,source_line_id,item_id,batch_code,check_quantity,qualified_quantity,unqualified_quantity,check_result,deleted) VALUES(?,1,4,?,1,?,10,NULL,10,?,?,?,0)",100+sourceLine,business==Business.SALES?119:116,sourceLine,new BigDecimal(split?"6":"10"),new BigDecimal(split?"4":"0"),split?2:1);
            return status;
        }

        private void configureSales() {
            var parent = mock(MesWmReturnSalesService.class);
            ReflectionTestUtils.setField(salesService, "returnSalesLineMapper", salesMapper);
            ReflectionTestUtils.setField(salesService, "returnSalesService", parent);
            ReflectionTestUtils.setField(salesService,"sourceService",mock(MesWmReturnSalesSourceService.class));ReflectionTestUtils.setField(salesService,"jdbcTemplate",jdbc);
            when(salesMapper.selectById(anyLong())).thenAnswer(call -> {
                var line = salesRow(call.getArgument(0), false);
                if (line.getQualityStatus() == PENDING) pendingSnapshots.incrementAndGet();
                return line;
            });
            when(salesMapper.selectByIdForUpdate(anyLong())).thenAnswer(call -> salesRow(call.getArgument(0), true));
            when(salesMapper.selectListByReturnId(anyLong())).thenAnswer(call -> jdbc.query(
                    "SELECT * FROM " + business.line + " WHERE return_id=? ORDER BY id", this::salesRow,
                    ((Number) call.getArgument(0)).longValue()));
            when(salesMapper.selectListByReturnIdForUpdate(anyLong())).thenAnswer(call -> jdbc.query(
                    "SELECT * FROM " + business.line + " WHERE return_id=? ORDER BY id FOR UPDATE", this::salesRow,
                    ((Number) call.getArgument(0)).longValue()));
            when(salesMapper.updateById(any(MesWmReturnSalesLineDO.class))).thenAnswer(call -> {
                MesWmReturnSalesLineDO row = call.getArgument(0);
                return update(row.getId(), row.getQualityStatus(), row.getQuantity());
            });
            when(salesMapper.insert(any(MesWmReturnSalesLineDO.class))).thenAnswer(call -> {
                MesWmReturnSalesLineDO row = call.getArgument(0);
                insert(row.getReturnId(), row.getItemId(), row.getBatchId(), 50L, row.getQuantity(), row.getQualityStatus(),row.getRqcId(),row.getOriginTransactionId());
                row.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
                return 1;
            });
            when(parent.validateReturnSalesExists(PARENT)).thenAnswer(call -> new MesWmReturnSalesDO()
                    .setId(PARENT).setStatus(lockedParentStatus()));
            doAnswer(call -> {
                Integer currentStatus = call.getArgument(1, Integer.class);
                Long documentId = call.getArgument(0, Long.class);
                jdbc.update("UPDATE " + business.header + " SET status=? WHERE id=?", currentStatus, documentId);
                return null;
            }).when(parent).updateReturnSalesStatus(anyLong(), anyInt());
        }

        private void configureIssue() {
            var parent = mock(MesWmReturnIssueService.class);
            ReflectionTestUtils.setField(issueService, "issueLineMapper", issueMapper);
            ReflectionTestUtils.setField(issueService, "issueService", parent);
            var qcMapper=mock(MesQcRqcMapper.class);ReflectionTestUtils.setField(issueService,"rqcMapper",qcMapper);
            when(qcMapper.selectOne(any(Wrapper.class))).thenAnswer(call->jdbc.queryForObject("SELECT * FROM mes_qc_rqc WHERE tenant_id=1 AND id=? AND source_doc_type=116 AND source_doc_id=1 AND source_line_id=? FOR UPDATE",(rs,index)->new MesQcRqcDO().setId(rs.getLong("id")).setStatus(rs.getInt("status")).setSourceDocType(116).setSourceDocId(1L).setSourceLineId(rs.getLong("source_line_id")).setItemId(rs.getLong("item_id")).setBatchCode(rs.getString("batch_code")).setCheckQuantity(rs.getBigDecimal("check_quantity")).setQualifiedQuantity(rs.getBigDecimal("qualified_quantity")).setUnqualifiedQuantity(rs.getBigDecimal("unqualified_quantity")).setCheckResult(rs.getInt("check_result")),100+inspectionLine.get(),inspectionLine.get()));
            when(issueMapper.selectById(anyLong())).thenAnswer(call -> {
                var line = issueRow(call.getArgument(0), false);
                if (line.getQualityStatus() == PENDING) pendingSnapshots.incrementAndGet();
                return line;
            });
            when(issueMapper.selectByIdForUpdate(anyLong())).thenAnswer(call -> issueRow(call.getArgument(0), true));
            when(issueMapper.selectListByIssueId(anyLong())).thenAnswer(call -> jdbc.query(
                    "SELECT * FROM " + business.line + " WHERE issue_id=? ORDER BY id", this::issueRow,
                    ((Number) call.getArgument(0)).longValue()));
            when(issueMapper.selectListByIssueIdForUpdate(anyLong())).thenAnswer(call -> jdbc.query(
                    "SELECT * FROM " + business.line + " WHERE issue_id=? ORDER BY id FOR UPDATE", this::issueRow,
                    ((Number) call.getArgument(0)).longValue()));
            when(issueMapper.updateById(any(MesWmReturnIssueLineDO.class))).thenAnswer(call -> {
                MesWmReturnIssueLineDO row = call.getArgument(0);
                return update(row.getId(), row.getQualityStatus(), row.getQuantity());
            });
            when(issueMapper.insert(any(MesWmReturnIssueLineDO.class))).thenAnswer(call -> {
                MesWmReturnIssueLineDO row = call.getArgument(0);
                insert(row.getIssueId(), row.getItemId(), row.getBatchId(), row.getMaterialStockId(), row.getQuantity(), row.getQualityStatus(),row.getRqcId(),null);
                row.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
                return 1;
            });
            when(parent.validateReturnIssueExists(PARENT)).thenAnswer(call -> new MesWmReturnIssueDO()
                    .setId(PARENT).setStatus(lockedParentStatus()));
            doAnswer(call -> {
                Integer currentStatus = call.getArgument(1, Integer.class);
                Long documentId = call.getArgument(0, Long.class);
                jdbc.update("UPDATE " + business.header + " SET status=? WHERE id=?", currentStatus, documentId);
                return null;
            }).when(parent).updateReturnIssueStatus(anyLong(), anyInt());
        }

        private MesWmReturnSalesLineDO salesRow(long id, boolean lock) {
            return jdbc.queryForObject("SELECT * FROM " + business.line + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                    this::salesRow, id);
        }

        private MesWmReturnSalesLineDO salesRow(ResultSet row, int ignored) throws SQLException {
            return new MesWmReturnSalesLineDO().setId(row.getLong("id")).setReturnId(row.getLong("return_id"))
                    .setItemId(row.getLong("item_id")).setBatchId(row.getLong("batch_id"))
                    .setQuantity(row.getBigDecimal("quantity")).setQualityStatus(row.getInt("quality_status")).setRqcId((Long)row.getObject("rqc_id")).setOriginTransactionId((Long)row.getObject("origin_transaction_id"));
        }

        private MesWmReturnIssueLineDO issueRow(long id, boolean lock) {
            return jdbc.queryForObject("SELECT * FROM " + business.line + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                    this::issueRow, id);
        }

        private MesWmReturnIssueLineDO issueRow(ResultSet row, int ignored) throws SQLException {
            return new MesWmReturnIssueLineDO().setId(row.getLong("id")).setIssueId(row.getLong("issue_id"))
                    .setMaterialStockId(row.getLong("material_stock_id")).setItemId(row.getLong("item_id"))
                    .setBatchId(row.getLong("batch_id")).setQuantity(row.getBigDecimal("quantity"))
                    .setQualityStatus(row.getInt("quality_status")).setRqcId((Long)row.getObject("rqc_id")).setRqcCheckFlag(true);
        }

        private int update(long id, int quality, BigDecimal quantity) {
            return jdbc.update("UPDATE " + business.line + " SET quality_status=?,quantity=COALESCE(?,quantity) WHERE id=?",
                    quality, quantity, id);
        }

        private void insert(long parent, long item, long batch, long stock, BigDecimal quantity, int quality,Long rqcId,Long origin) {
            jdbc.update("INSERT INTO " + business.line + "(" + business.parent
                            + ",item_id,batch_id,material_stock_id,quantity,quality_status,rqc_id,origin_transaction_id) VALUES(?,?,?,?,?,?,?,?)",
                    parent, item, batch, stock, quantity, quality,rqcId,origin);
        }

        void verifyCurrentListUsed(int times) {
            if (business == Business.SALES) {
                verify(salesMapper, times(times)).selectListByReturnIdForUpdate(PARENT);
                verify(salesMapper, never()).selectListByReturnId(anyLong());
            } else {
                verify(issueMapper, times(times)).selectListByIssueIdForUpdate(PARENT);
                verify(issueMapper, never()).selectListByIssueId(anyLong());
            }
        }
    }
}
