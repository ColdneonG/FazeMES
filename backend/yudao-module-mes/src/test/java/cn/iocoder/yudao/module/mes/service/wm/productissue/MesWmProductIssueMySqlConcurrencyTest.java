package cn.iocoder.yudao.module.mes.service.wm.productissue;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.detail.MesWmProductIssueDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.line.MesWmProductIssueLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productissue.*;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductIssueStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderBomService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_PRODUCT_ISSUE_STATUS_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real MyBatis-Plus mappers, real transactional services and native InnoDB RR read views.
 * Opt in only with an EMPTY issue_ownership_it_* schema on the disposable local test instance.
 * This fixture never initializes, migrates or connects to an application database.
 */
@EnabledIfEnvironmentVariable(named = "MES_ISSUE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:59685/issue_ownership_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmProductIssueMySqlConcurrencyTest {

    private static final Set<String> TABLES = Set.of("mes_wm_product_issue", "mes_wm_product_issue_line",
            "mes_wm_product_issue_detail");
    private static final long ISSUE = 1L;
    private static final long LINE = 11L;
    private static final long DETAIL = 21L;
    private static final int PREPARE = MesWmProductIssueStatusEnum.PREPARE.getStatus();
    private static final int APPROVING = MesWmProductIssueStatusEnum.APPROVING.getStatus();
    private static final int APPROVED = MesWmProductIssueStatusEnum.APPROVED.getStatus();
    private static final int FINISHED = MesWmProductIssueStatusEnum.FINISHED.getStatus();
    private DataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private String schema;
    private boolean ownsTables;
    private MesWmProductIssueMapper issueMapper;
    private MesWmProductIssueLineMapper lineMapper;
    private MesWmProductIssueDetailMapper detailMapper;
    private MesWmProductIssueService issues;
    private MesWmProductIssueLineService lines;
    private MesWmProductIssueDetailService details;
    private MesWmTransactionService postings;
    private final LockObservation lockObservation = new LockObservation();

    enum Child {
        LINE("mes_wm_product_issue_line", PREPARE),
        DETAIL("mes_wm_product_issue_detail", APPROVING);

        final String table;
        final int editableStatus;

        Child(String table, int editableStatus) {
            this.table = table;
            this.editableStatus = editableStatus;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("MES_ISSUE_MYSQL_URL");
        URI location = URI.create(url.substring(5));
        assertEquals("127.0.0.1", location.getHost());
        assertEquals(59685, location.getPort(), "Only the disposable test instance is permitted");
        schema = location.getPath().substring(1);
        assertTrue(schema.matches("issue_ownership_it_[A-Za-z0-9_]+"));
        source = new DriverManagerDataSource(url, System.getenv("MES_ISSUE_MYSQL_USER"),
                System.getenv("MES_ISSUE_MYSQL_PASSWORD"));
        jdbc = checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema)
                .isEmpty(), "Use a dedicated EMPTY test schema");
        ownsTables = true;
        String audit = ",creator VARCHAR(64),updater VARCHAR(64),create_time DATETIME,update_time DATETIME,"
                + "deleted BIT NOT NULL DEFAULT 0";
        jdbc.execute("CREATE TABLE mes_wm_product_issue(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(64),"
                + "name VARCHAR(128),workstation_id BIGINT,work_order_id BIGINT,task_id BIGINT,issue_date DATETIME,"
                + "required_time DATETIME,status INT,remark VARCHAR(500)" + audit + ") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_issue_line(id BIGINT AUTO_INCREMENT PRIMARY KEY,issue_id BIGINT,"
                + "item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,remark VARCHAR(500)" + audit
                + ",KEY idx_parent(issue_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_issue_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,issue_id BIGINT,"
                + "line_id BIGINT,material_stock_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,"
                + "batch_code VARCHAR(64),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,remark VARCHAR(500)" + audit
                + ",KEY idx_parent(issue_id),KEY idx_line(line_id)) ENGINE=InnoDB");

        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setLogImpl(NoLoggingImpl.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO)));
        factory.setPlugins(lockObservation);
        var sessions = Objects.requireNonNull(factory.getObject());
        sessions.getConfiguration().addMapper(MesWmProductIssueMapper.class);
        sessions.getConfiguration().addMapper(MesWmProductIssueLineMapper.class);
        sessions.getConfiguration().addMapper(MesWmProductIssueDetailMapper.class);
        var template = new SqlSessionTemplate(sessions);
        issueMapper = template.getMapper(MesWmProductIssueMapper.class);
        lineMapper = template.getMapper(MesWmProductIssueLineMapper.class);
        detailMapper = template.getMapper(MesWmProductIssueDetailMapper.class);

        var issueTarget = new MesWmProductIssueServiceImpl();
        var lineTarget = new MesWmProductIssueLineServiceImpl();
        var detailTarget = new MesWmProductIssueDetailServiceImpl();
        issues = proxied(issueTarget, manager);
        lines = proxied(lineTarget, manager);
        details = proxied(detailTarget, manager);
        ReflectionTestUtils.setField(issueTarget, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(issueTarget, "issueLineService", lines);
        ReflectionTestUtils.setField(issueTarget, "issueDetailService", details);
        ReflectionTestUtils.setField(issueTarget, "workstationService", mock(MesMdWorkstationService.class));
        var orders = mock(MesProWorkOrderService.class);
        when(orders.validateWorkOrderExistsForUpdate(100L)).thenReturn(new MesProWorkOrderDO().setId(100L).setStatus(1));
        ReflectionTestUtils.setField(issueTarget, "workOrderService", orders);
        ReflectionTestUtils.setField(issueTarget, "kittingService", mock(MesProKittingService.class));
        postings = mock(MesWmTransactionService.class);
        when(postings.createTransaction(any())).thenReturn(900L);
        ReflectionTestUtils.setField(issueTarget, "wmTransactionService", postings);
        var warehouses = mock(MesWmWarehouseService.class);
        when(warehouses.getWarehouseByCode(anyString())).thenReturn(new MesWmWarehouseDO().setId(101L));
        ReflectionTestUtils.setField(issueTarget, "warehouseService", warehouses);
        var locations = mock(MesWmWarehouseLocationService.class);
        when(locations.getWarehouseLocationByCode(anyString())).thenReturn(new MesWmWarehouseLocationDO().setId(102L));
        ReflectionTestUtils.setField(issueTarget, "locationService", locations);
        var areas = mock(MesWmWarehouseAreaService.class);
        when(areas.getWarehouseAreaByCode(anyString())).thenReturn(new MesWmWarehouseAreaDO().setId(103L));
        ReflectionTestUtils.setField(issueTarget, "areaService", areas);
        ReflectionTestUtils.setField(lineTarget, "issueLineMapper", lineMapper);
        ReflectionTestUtils.setField(lineTarget, "issueService", issues);
        ReflectionTestUtils.setField(lineTarget, "issueDetailService", details);
        ReflectionTestUtils.setField(lineTarget, "itemService", mock(MesMdItemService.class));
        var bom = mock(MesProWorkOrderBomService.class);
        when(bom.getWorkOrderBomListByWorkOrderId(anyLong())).thenReturn(List.of());
        ReflectionTestUtils.setField(lineTarget, "workOrderBomService", bom);
        ReflectionTestUtils.setField(detailTarget, "issueDetailMapper", detailMapper);
        ReflectionTestUtils.setField(detailTarget, "issueService", issues);
        ReflectionTestUtils.setField(detailTarget, "issueLineService", lines);
        ReflectionTestUtils.setField(detailTarget, "warehouseAreaService", areas);
        ReflectionTestUtils.setField(detailTarget, "materialStockService", mock(MesWmMaterialStockService.class));
    }

    @AfterEach
    void cleanup() {
        if (!ownsTables) return;
        var cleanup = checked();
        List<String> tables = cleanup.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=?", String.class, schema);
        assertTrue(TABLES.containsAll(tables), "Unexpected tables: refusing cleanup");
        for (String table : tables) cleanup.execute("DROP TABLE `" + table + "`");
    }

    @ParameterizedTest
    @EnumSource(Child.class)
    void mutationWaitingForParentLockRejectsTheNewlyCompletedStatus(Child child) throws Exception {
        seed(child.editableStatus);
        var failure = raceParentWriteWithMutation(child, "UPDATE mes_wm_product_issue SET status=" + FINISHED + " WHERE id=1");
        assertEquals(WM_PRODUCT_ISSUE_STATUS_INVALID.getCode(), failure.getCode());
        assertEquals(FINISHED, jdbc.queryForObject("SELECT status FROM mes_wm_product_issue WHERE id=1", Integer.class));
        assertEquals(0, BigDecimal.TEN.compareTo(jdbc.queryForObject(
                "SELECT quantity FROM " + child.table + " WHERE id=?", BigDecimal.class, childId(child))));
        verifyNoInteractions(postings);
    }

    @ParameterizedTest
    @EnumSource(Child.class)
    void childDeletedWhileMutationWaitsMustBeRecheckedWithACurrentRead(Child child) throws Exception {
        seed(child.editableStatus);
        raceParentWriteWithMutation(child, "UPDATE " + child.table + " SET deleted=1 WHERE id=" + childId(child));
        assertEquals(1, jdbc.queryForObject("SELECT deleted+0 FROM " + child.table + " WHERE id=?",
                Integer.class, childId(child)));
        assertEquals(0, BigDecimal.TEN.compareTo(jdbc.queryForObject(
                "SELECT quantity FROM " + child.table + " WHERE id=?", BigDecimal.class, childId(child))));
        verifyNoInteractions(postings);
    }

    @ParameterizedTest
    @EnumSource(Child.class)
    void finishedHistoryCannotBeMovedToAnEditableDocument(Child child) {
        seed(FINISHED);
        jdbc.update("INSERT INTO mes_wm_product_issue(id,code,work_order_id,status) VALUES(2,'TARGET',100,?)", child.editableStatus);
        jdbc.update("INSERT INTO mes_wm_product_issue_line(id,issue_id,item_id,quantity) VALUES(12,2,7,10)");
        if (child == Child.LINE) {
            var request = BeanUtils.toBean(lineMapper.selectById(LINE), MesWmProductIssueLineSaveReqVO.class);
            request.setIssueId(2L);
            assertThrows(ServiceException.class, () -> lines.updateProductIssueLine(request));
        } else {
            var request = BeanUtils.toBean(detailMapper.selectById(DETAIL), MesWmProductIssueDetailSaveReqVO.class);
            request.setIssueId(2L);
            request.setLineId(12L);
            assertThrows(ServiceException.class, () -> details.updateProductIssueDetail(request));
        }
        assertEquals(ISSUE, jdbc.queryForObject("SELECT issue_id FROM " + child.table + " WHERE id=?",
                Long.class, childId(child)));
        verifyNoInteractions(postings);
    }

    @Test
    void finishUsesTheCurrentLockedQuantitiesDespiteAnExistingRepeatableReadSnapshot() {
        seed(APPROVED);
        transaction.executeWithoutResult(status -> {
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            assertQuantity("10", detailMapper.selectById(DETAIL).getQuantity());
            commitOtherConnection("UPDATE mes_wm_product_issue_line SET quantity=6 WHERE id=11",
                    "UPDATE mes_wm_product_issue_detail SET quantity=6 WHERE id=21");
            assertQuantity("10", detailMapper.selectById(DETAIL).getQuantity(), "The RR snapshot must actually remain old");
            issues.finishProductIssue(ISSUE);
        });
        var posted = org.mockito.ArgumentCaptor.forClass(MesWmTransactionSaveReqDTO.class);
        verify(postings, times(2)).createTransaction(posted.capture());
        assertQuantity("-6", posted.getAllValues().get(0).getQuantity());
        assertQuantity("6", posted.getAllValues().get(1).getQuantity());
        assertEquals(900L, posted.getAllValues().get(1).getRelatedTransactionId());
        assertEquals(FINISHED, jdbc.queryForObject("SELECT status FROM mes_wm_product_issue WHERE id=1", Integer.class));
        assertTrue(lockObservation.currentTables.contains("mes_wm_product_issue_line"));
        assertTrue(lockObservation.currentTables.contains("mes_wm_product_issue_detail"));
    }

    @Test
    void finishRejectsCurrentOwnershipCorruptionInsteadOfTrustingTheOldValidSnapshot() {
        seed(APPROVED);
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(LINE, detailMapper.selectById(DETAIL).getLineId());
            commitOtherConnection("UPDATE mes_wm_product_issue_detail SET line_id=999 WHERE id=21");
            assertEquals(LINE, detailMapper.selectById(DETAIL).getLineId(), "The previous valid view must still be visible");
            issues.finishProductIssue(ISSUE);
        }));
        assertEquals(APPROVED, jdbc.queryForObject("SELECT status FROM mes_wm_product_issue WHERE id=1", Integer.class));
        verifyNoInteractions(postings);
    }

    @Test
    void crossOwnedDetailsAreRejectedBeforeAnyByLineLockingScan() {
        seed(APPROVED);
        jdbc.update("INSERT INTO mes_wm_product_issue(id,code,work_order_id,status) VALUES(2,'ISSUE-2',100,?)", APPROVED);
        jdbc.update("INSERT INTO mes_wm_product_issue_line(id,issue_id,item_id,quantity,batch_id) VALUES(12,2,7,10,8)");
        jdbc.update("UPDATE mes_wm_product_issue_detail SET line_id=12 WHERE id=21");
        jdbc.update("INSERT INTO mes_wm_product_issue_detail(id,issue_id,line_id,material_stock_id,item_id,quantity,"
                + "batch_id,batch_code,warehouse_id,location_id,area_id) VALUES(22,2,11,9,7,10,8,'BATCH-8',1,2,3)");

        var rejected = assertThrows(ServiceException.class, () -> issues.finishProductIssue(ISSUE));

        assertEquals(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH.getCode(), rejected.getCode());
        assertTrue(lockObservation.currentTables.contains("mes_wm_product_issue_detail"),
                "The real by-issue locking read must have found the corrupt detail");
        assertEquals(0, lockObservation.detailLineScans.get(),
                "Reject the cross-owned by-issue row before taking a second issue's detail lock through line_id");
        assertEquals(APPROVED, jdbc.queryForObject("SELECT status FROM mes_wm_product_issue WHERE id=1", Integer.class));
        assertEquals(APPROVED, jdbc.queryForObject("SELECT status FROM mes_wm_product_issue WHERE id=2", Integer.class));
        verifyNoInteractions(postings);
    }

    private ServiceException raceParentWriteWithMutation(Child child, String change) throws Exception {
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "issue-native-mutation");
            mutationThread.set(thread);
            return thread;
        });
        CountDownLatch reachedParentLock = new CountDownLatch(1);
        lockObservation.nextParentLock.set(reachedParentLock);
        try (Connection writer = source.getConnection()) {
            writer.setAutoCommit(false);
            try (var lock = writer.prepareStatement("SELECT id FROM mes_wm_product_issue WHERE id=1 FOR UPDATE")) {
                try (var row = lock.executeQuery()) { assertTrue(row.next()); }
            }
            try (var write = writer.prepareStatement(change)) { assertEquals(1, write.executeUpdate()); }
            var mutation = executor.submit(() -> transaction.executeWithoutResult(status -> mutate(child)));
            if (!reachedParentLock.await(5, TimeUnit.SECONDS)) {
                if (mutation.isDone()) {
                    try {
                        mutation.get();
                    } catch (ExecutionException failure) {
                        throw new AssertionError("Mutation failed before reaching the parent locking read", failure.getCause());
                    }
                    fail("Mutation completed without reaching the parent locking read");
                }
                Thread thread = mutationThread.get();
                fail("Mutation did not reach the real parent locking read; observed SQL=" + lockObservation.statements
                        + "; worker=" + (thread == null ? "not started" : thread.getState() + " "
                        + Arrays.toString(thread.getStackTrace())));
            }
            assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));
            writer.commit();
            var failure = assertThrows(ExecutionException.class, () -> mutation.get(10, TimeUnit.SECONDS));
            return assertInstanceOf(ServiceException.class, failure.getCause());
        } finally {
            lockObservation.nextParentLock.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void mutate(Child child) {
        if (child == Child.LINE) {
            var request = BeanUtils.toBean(lineMapper.selectById(LINE), MesWmProductIssueLineSaveReqVO.class);
            request.setQuantity(new BigDecimal("20"));
            lines.updateProductIssueLine(request);
        } else {
            var request = BeanUtils.toBean(detailMapper.selectById(DETAIL), MesWmProductIssueDetailSaveReqVO.class);
            request.setQuantity(new BigDecimal("20"));
            details.updateProductIssueDetail(request);
        }
    }

    private void commitOtherConnection(String... writes) {
        // JdbcTemplate would join the outer transaction, so use an explicitly separate connection.
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            for (String sql : writes) {
                try (var statement = connection.prepareStatement(sql)) { assertEquals(1, statement.executeUpdate()); }
            }
            connection.commit();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void seed(int status) {
        jdbc.update("INSERT INTO mes_wm_product_issue(id,code,name,work_order_id,status) VALUES(1,'ISSUE-1','Issue',100,?)", status);
        jdbc.update("INSERT INTO mes_wm_product_issue_line(id,issue_id,item_id,quantity,batch_id) VALUES(11,1,7,10,8)");
        jdbc.update("INSERT INTO mes_wm_product_issue_detail(id,issue_id,line_id,material_stock_id,item_id,quantity,"
                + "batch_id,batch_code,warehouse_id,location_id,area_id) VALUES(21,1,11,9,7,10,8,'BATCH-8',1,2,3)");
    }

    private JdbcTemplate checked() {
        assertTrue(schema.matches("issue_ownership_it_[A-Za-z0-9_]+"));
        var checked = new JdbcTemplate(source);
        assertEquals(schema, checked.queryForObject("SELECT DATABASE()", String.class), "Refusing another database");
        return checked;
    }

    private static long childId(Child child) { return child == Child.LINE ? LINE : DETAIL; }

    private static void assertQuantity(String expected, BigDecimal actual) { assertQuantity(expected, actual, "Quantity differs"); }

    private static void assertQuantity(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** Observes SQL entering the real mapper, without replacing or mocking a locking read. */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "queryCursor",
                    args = {MappedStatement.class, Object.class, RowBounds.class})
    })
    static class LockObservation implements Interceptor {
        final AtomicReference<CountDownLatch> nextParentLock = new AtomicReference<>();
        final Set<String> currentTables = ConcurrentHashMap.newKeySet();
        final AtomicInteger detailLineScans = new AtomicInteger();
        final Queue<String> statements = new ConcurrentLinkedQueue<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String sql = statement.getBoundSql(invocation.getArgs()[1]).getSql()
                    .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            statements.add(Thread.currentThread().getName() + ": " + sql);
            if (sql.contains("for update")) {
                if (sql.matches(".*\\bfrom mes_wm_product_issue_detail\\b.*\\bwhere\\b.*\\bline_id\\s*=.*")) {
                    detailLineScans.incrementAndGet();
                }
                for (String table : TABLES) {
                    if (sql.matches(".*\\bfrom " + table + "\\b.*")) currentTables.add(table);
                }
                if (sql.matches(".*\\bfrom mes_wm_product_issue\\b.*")) {
                    CountDownLatch waiting = nextParentLock.getAndSet(null);
                    if (waiting != null) waiting.countDown();
                }
            }
            return invocation.proceed();
        }
    }
}
