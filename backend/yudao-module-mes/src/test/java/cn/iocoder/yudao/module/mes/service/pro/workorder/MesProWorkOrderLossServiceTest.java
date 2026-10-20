package cn.iocoder.yudao.module.mes.service.pro.workorder;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderLossVO.CloseRequest;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.MesProWorkOrderLossController;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesProWorkOrderLossServiceTest {
    JdbcTemplate jdbc;
    MesProWorkOrderLossService service;
    MesProTaskService taskService;
    MesProKittingService kittingService;
    DataSourceTransactionManager transactions;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:loss_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY,parent_id BIGINT DEFAULT 0,quantity DECIMAL(20,6),quantity_changed DECIMAL(20,6),quantity_produced DECIMAL(20,6),status INT,type INT,finish_date TIMESTAMP,updater VARCHAR(64),update_time TIMESTAMP,tenant_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_task(id BIGINT PRIMARY KEY,work_order_id BIGINT,route_id BIGINT,process_id BIGINT,status INT,tenant_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_route_process(id BIGINT PRIMARY KEY,route_id BIGINT,process_id BIGINT,sort INT,key_flag BOOLEAN,tenant_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_feedback(id BIGINT PRIMARY KEY,work_order_id BIGINT,task_id BIGINT,route_id BIGINT,process_id BIGINT,feedback_quantity DECIMAL(20,6),qualified_quantity DECIMAL(20,6),unqualified_quantity DECIMAL(20,6),uncheck_quantity DECIMAL(20,6),status INT,tenant_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,work_order_id BIGINT,quantity DECIMAL(20,6),tenant_id BIGINT,deleted INT DEFAULT 0)");
        try (var connection=ds.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource("../../database/migrations/20261019_workorder_loss_closure.sql")); }
        MesProWorkOrderLossService target = new MesProWorkOrderLossService(); ReflectionTestUtils.setField(target,"jdbc",jdbc);
        taskService = mock(MesProTaskService.class); kittingService = mock(MesProKittingService.class);
        ReflectionTestUtils.setField(target,"taskService",taskService);ReflectionTestUtils.setField(target,"kittingService",kittingService);ReflectionTestUtils.setField(target,"costService",mock(MesCostService.class));
        doAnswer(call -> { jdbc.update("UPDATE mes_pro_task SET status=4 WHERE work_order_id=1 AND status<>5");return null; }).when(taskService).finishTaskByOrderId(1L);
        transactions = new DataSourceTransactionManager(ds);
        ProxyFactory factory = new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));service = (MesProWorkOrderLossService)factory.getProxy();
        authenticate();
        jdbc.update("INSERT INTO mes_pro_work_order(id,quantity,quantity_changed,quantity_produced,status,type,tenant_id) VALUES(1,100,0,90,1,1,1)");
        jdbc.update("INSERT INTO mes_pro_task VALUES(1,1,1,10,4,1,0),(2,1,1,20,0,1,0)");
        jdbc.update("INSERT INTO mes_pro_route_process VALUES(1,1,10,10,FALSE,1,0),(2,1,20,20,TRUE,1,0)");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,1,1,1,10,100,90,10,0,4,1,0),(2,1,2,1,20,90,90,0,0,4,1,0)");
    }
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    void authenticate() { TenantContextHolder.setTenantId(1L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(42L), null, List.of())); }
    CloseRequest request() { CloseRequest r = new CloseRequest(); r.setId(1L);r.setExpectedProducedQuantity(new BigDecimal("90"));r.setReason("前序10件经确认损耗，剩余合格品已全部完工入库");return r; }
    @Test void upstreamRejectsCanCloseWithAuditedLossWithoutRewritingProduction() {
        MesProductionIntegrityService ordinary = new MesProductionIntegrityService();ReflectionTestUtils.setField(ordinary,"jdbc",jdbc);
        assertThrows(ServiceException.class, () -> ordinary.validateWorkOrderFinish(1L));
        var preview=service.preview(1L); assertEquals(0,new BigDecimal("10").compareTo(preview.getLossQuantity()));
        service.close(request());
        assertEquals(2,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=1",Integer.class));
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT quantity FROM mes_pro_work_order WHERE id=1",BigDecimal.class)));
        assertEquals(0,new BigDecimal("90").compareTo(jdbc.queryForObject("SELECT quantity_produced FROM mes_pro_work_order WHERE id=1",BigDecimal.class)));
        assertEquals(42L,service.record(1L).getClosedBy());
        assertEquals(request().getReason(),service.record(1L).getReason());
        assertEquals(0,jdbc.queryForObject("SELECT quantity_changed FROM mes_pro_work_order WHERE id=1",BigDecimal.class).signum());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_feedback WHERE status=4",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_task WHERE status=4",Integer.class));
        assertNotNull(service.record(1L).getClosedAt());
        assertTrue(jdbc.queryForObject("SELECT process_evidence FROM mes_pro_work_order_loss",String.class).contains("unqualifiedQuantity"));
        verify(kittingService).releaseForClosedOrder(1L);
    }
    @Test void unfinishedDownstreamWorkCannotBeLabeledAsLoss() {
        jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=80,qualified_quantity=80 WHERE id=2");jdbc.update("UPDATE mes_pro_work_order SET quantity_produced=80 WHERE id=1");
        assertThrows(ServiceException.class,()->service.preview(1L));assertThrows(ServiceException.class,()->service.close(request()));assertOpen();
    }
    @Test void unfinishedFirstStageAndMissingRouteProcessesCannotBeShortClosed() {
        jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=90,qualified_quantity=80 WHERE id=1");jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=80,qualified_quantity=80 WHERE id=2");jdbc.update("UPDATE mes_pro_work_order SET quantity_produced=80 WHERE id=1");
        assertThrows(ServiceException.class,()->service.close(request()));assertOpen();
    }
    @Test void pendingDraftsAndInvalidConservationAreRejected() {
        jdbc.update("UPDATE mes_pro_feedback SET status=3 WHERE id=2");assertThrows(ServiceException.class,()->service.close(request()));
        jdbc.update("UPDATE mes_pro_feedback SET status=0 WHERE id=2");assertThrows(ServiceException.class,()->service.close(request()));
        jdbc.update("UPDATE mes_pro_feedback SET status=4,qualified_quantity=91 WHERE id=2");assertThrows(ServiceException.class,()->service.close(request()));assertOpen();
    }
    @Test void stockAndUnfinishedChildrenMustBeHandledBeforeClosure() {
        jdbc.update("INSERT INTO mes_wm_material_stock VALUES(1,1,1,1,0)");assertThrows(ServiceException.class,()->service.close(request()));
        jdbc.update("UPDATE mes_wm_material_stock SET quantity=0");
        jdbc.update("INSERT INTO mes_pro_work_order(id,parent_id,quantity,quantity_produced,status,type,tenant_id) VALUES(2,1,100,90,1,1,1)");assertThrows(ServiceException.class,()->service.close(request()));
        jdbc.update("UPDATE mes_pro_work_order SET status=2 WHERE id=2");assertDoesNotThrow(()->service.close(request()));
    }
    @Test void stalePreviewAndRepeatedClosureDoNotChangeHistory() {
        CloseRequest stale=request();stale.setExpectedProducedQuantity(new BigDecimal("89"));assertThrows(ServiceException.class,()->service.close(stale));assertOpen();
        service.close(request());assertThrows(ServiceException.class,()->service.close(request()));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_work_order_loss",Integer.class));
    }
    @Test void tenantAndAuthenticationChecksProtectAudit() {
        TenantContextHolder.setTenantId(2L);assertThrows(ServiceException.class,()->service.preview(1L));assertThrows(ServiceException.class,()->service.close(request()));assertThrows(ServiceException.class,()->service.record(1L));
        TenantContextHolder.setTenantId(1L);SecurityContextHolder.clearContext();assertThrows(ServiceException.class,()->service.close(request()));assertOpen();
    }
    @Test void failedReservationReleaseRollsBackAuditTasksAndOrder() {
        doThrow(new IllegalStateException("reservation conflict")).when(kittingService).releaseForClosedOrder(1L);
        assertThrows(IllegalStateException.class,()->service.close(request()));assertOpen();assertEquals(0,jdbc.queryForObject("SELECT status FROM mes_pro_task WHERE id=2",Integer.class));
    }
    @Test void noActualUpstreamLossUsesOrdinaryFinishInstead() {
        jdbc.update("UPDATE mes_pro_feedback SET qualified_quantity=100,unqualified_quantity=0 WHERE id=1");jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=100,qualified_quantity=100 WHERE id=2");jdbc.update("UPDATE mes_pro_work_order SET quantity_produced=100 WHERE id=1");
        assertThrows(ServiceException.class,()->service.close(request()));assertOpen();
    }
    @Test void finalRejectsAreNotMisreportedAsUpstreamLoss() {
        jdbc.update("UPDATE mes_pro_feedback SET qualified_quantity=85,unqualified_quantity=5 WHERE id=2");
        assertEquals(0,new BigDecimal("10").compareTo(service.preview(1L).getLossQuantity()));service.close(request());
        assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("SELECT unqualified_quantity FROM mes_pro_feedback WHERE id=2",BigDecimal.class)));
    }
    @Test void allUpstreamUnitsLostCanBeClosedWithoutFakeFinalOutput() {
        jdbc.update("UPDATE mes_pro_feedback SET qualified_quantity=0,unqualified_quantity=100 WHERE id=1");jdbc.update("DELETE FROM mes_pro_feedback WHERE id=2");jdbc.update("UPDATE mes_pro_work_order SET quantity_produced=0 WHERE id=1");
        CloseRequest r=request();r.setExpectedProducedQuantity(BigDecimal.ZERO);service.close(r);assertEquals(0,service.record(1L).getProducedQuantity().signum());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_feedback",Integer.class));
    }
    @Test void reportCommittedWhileCloseWaitsIsRecheckedBeforeAnyClosure() throws Exception {
        CountDownLatch orderLocked = new CountDownLatch(1), commit = new CountDownLatch(1), closeStarted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> reporter=workers.submit(()->{ authenticate();try {new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM mes_pro_work_order WHERE id=1 FOR UPDATE",Long.class);orderLocked.countDown();
                try {if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("INSERT INTO mes_pro_feedback VALUES(3,1,2,1,20,1,0,0,1,3,1,0)");
            });} finally {TenantContextHolder.clear();SecurityContextHolder.clearContext();}});
            assertTrue(orderLocked.await(5,TimeUnit.SECONDS));
            Future<?> closer=workers.submit(()->{authenticate();closeStarted.countDown();try {assertThrows(ServiceException.class,()->service.close(request()));}finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}});
            assertTrue(closeStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->closer.get(150,TimeUnit.MILLISECONDS));commit.countDown();reporter.get(5,TimeUnit.SECONDS);closer.get(5,TimeUnit.SECONDS);assertOpen();
        } finally {commit.countDown();workers.shutdownNow();}
    }
    @Test void explicitReasonAndQuantityAreRequiredAndPreviewKeepsDecimalPrecision() throws Exception {
        CloseRequest r=request();r.setReason("  ");assertThrows(ServiceException.class,()->service.close(r));
        r.setReason("x".repeat(501));assertThrows(ServiceException.class,()->service.close(r));
        r.setReason("confirmed");r.setExpectedProducedQuantity(new BigDecimal("90.0000001"));assertThrows(ServiceException.class,()->service.close(r));assertOpen();
        String json=JsonUtils.toJsonString(service.preview(1L));assertTrue(json.contains("\"plannedQuantity\":\"100.000000\""));
        assertTrue(MesProWorkOrderLossController.class.getMethod("close",CloseRequest.class).getAnnotation(PreAuthorize.class).value().contains("mes:pro-work-order:update"));
        assertTrue(MesProWorkOrderLossController.class.getMethod("record",Long.class).getAnnotation(PreAuthorize.class).value().contains("mes:pro-work-order:query"));
    }
    private void assertOpen(){assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_work_order_loss",Integer.class));}
}
