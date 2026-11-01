package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.machinery.MesDvMachineryDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.MesDvRepairDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.machinery.MesDvMachineryMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.repair.MesDvRepairMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import cn.iocoder.yudao.module.mes.service.dv.machinery.MesDvMachineryServiceImpl;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 rows, real domain service implementations, and transactional failure/rollback checks. */
class MesAgentExecutionServiceTest {
    JdbcTemplate jdbc;
    SecurityFrameworkService security;
    MesAgentExecutionService service;
    MesProductionIntegrityService integrity;
    MesProWorkOrderMapper orderMapper;
    MesProTaskService tasks;
    MesProKittingService kitting;
    MesCostService costs;
    TransactionTemplate tx;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:agent_execution_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        String migration=Files.readString(Path.of("../../database/migrations/20261018_broky_cost.sql"));
        migration=migration.substring(0,migration.indexOf("-- Cost permissions")).replace("b'0'","0");
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));}
        jdbc.execute("CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),status INT,type INT,quantity DECIMAL(18,6),finish_date DATETIME,cancel_date DATETIME,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_dv_machinery(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),status INT,remark VARCHAR(100),brand VARCHAR(100),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_dv_repair(id BIGINT PRIMARY KEY,tenant_id BIGINT,machinery_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_work_order_loss(tenant_id BIGINT,work_order_id BIGINT,loss_quantity DECIMAL(18,6),allocation_method VARCHAR(100),allocation_reason VARCHAR(100),allocation_by BIGINT,allocation_at DATETIME)");
        jdbc.execute("CREATE TABLE mes_pro_task(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,process_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_feedback(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,item_id BIGINT,biz_type INT,biz_id BIGINT,biz_line_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_produce_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,produce_id BIGINT,quality_status INT,deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,code,name,status,type,quantity) VALUES(10,1,'WO-DRAFT','草稿工单',0,0,10),(20,1,'WO-FINISH','可完成工单',1,0,10),(30,1,'WO-CANCEL','可取消工单',1,0,10),(40,1,'WO-COST','成本工单',2,0,1),(90,2,'PRIVATE','其他租户',0,0,10)");
        jdbc.update("INSERT INTO mes_dv_machinery VALUES(1,1,'DV-001','设备',2,'保留备注','保留品牌',0),(2,2,'PRIVATE','其他租户',2,'','',0)");
        TenantContextHolder.setTenantId(1L);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        security=mock(SecurityFrameworkService.class);when(security.hasPermission(anyString())).thenReturn(true);
        integrity=mock(MesProductionIntegrityService.class);
        costs=new MesCostService();ReflectionTestUtils.setField(costs,"jdbc",jdbc);ReflectionTestUtils.setField(costs,"integrityService",integrity);
        tasks=mock(MesProTaskService.class);kitting=mock(MesProKittingService.class);
        orderMapper=mock(MesProWorkOrderMapper.class);
        when(orderMapper.selectByIdForUpdate(anyLong())).thenAnswer(call->{
            Long id=call.getArgument(0);var rows=jdbc.queryForList("SELECT * FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",id,TenantContextHolder.getRequiredTenantId());
            if(rows.isEmpty())return null;var row=rows.get(0);return new MesProWorkOrderDO().setId(id).setStatus(((Number)row.get("status")).intValue()).setType(((Number)row.get("type")).intValue());
        });
        when(orderMapper.updateById(any(MesProWorkOrderDO.class))).thenAnswer(call->{MesProWorkOrderDO row=call.getArgument(0);return jdbc.update("UPDATE mes_pro_work_order SET status=?,finish_date=?,cancel_date=? WHERE id=? AND tenant_id=?",row.getStatus(),row.getFinishDate(),row.getCancelDate(),row.getId(),TenantContextHolder.getRequiredTenantId());});
        var workOrders=new MesProWorkOrderServiceImpl();ReflectionTestUtils.setField(workOrders,"workOrderMapper",orderMapper);ReflectionTestUtils.setField(workOrders,"mesCostService",costs);ReflectionTestUtils.setField(workOrders,"integrityService",integrity);ReflectionTestUtils.setField(workOrders,"taskService",tasks);ReflectionTestUtils.setField(workOrders,"kittingService",kitting);
        var machineryMapper=mock(MesDvMachineryMapper.class);
        when(machineryMapper.selectForUpdate(anyLong())).thenAnswer(call->{long id=call.getArgument(0);var rows=jdbc.queryForList("SELECT status FROM mes_dv_machinery WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",id,TenantContextHolder.getRequiredTenantId());return rows.isEmpty()?null:new MesDvMachineryDO().setId(id).setStatus(((Number)rows.get(0).get("status")).intValue());});
        when(machineryMapper.updateById(any(MesDvMachineryDO.class))).thenAnswer(call->{MesDvMachineryDO row=call.getArgument(0);assertNull(row.getBrand());assertNull(row.getName());return jdbc.update("UPDATE mes_dv_machinery SET status=? WHERE id=? AND tenant_id=?",row.getStatus(),row.getId(),TenantContextHolder.getRequiredTenantId());});
        var repairMapper=mock(MesDvRepairMapper.class);
        when(repairMapper.selectUnfinishedForUpdate(anyLong())).thenAnswer(call->jdbc.query("SELECT id FROM mes_dv_repair WHERE tenant_id=? AND machinery_id=? AND status<>4 AND deleted=0 FOR UPDATE",(rs,index)->new MesDvRepairDO().setId(rs.getLong(1)),TenantContextHolder.getRequiredTenantId(),call.getArgument(0)));
        var machinery=new MesDvMachineryServiceImpl();ReflectionTestUtils.setField(machinery,"machineryMapper",machineryMapper);ReflectionTestUtils.setField(machinery,"repairMapper",repairMapper);
        var target=new MesAgentExecutionService();ReflectionTestUtils.setField(target,"jdbc",jdbc);ReflectionTestUtils.setField(target,"security",security);ReflectionTestUtils.setField(target,"workOrders",workOrders);ReflectionTestUtils.setField(target,"costs",costs);ReflectionTestUtils.setField(target,"integrity",integrity);ReflectionTestUtils.setField(target,"machinery",machinery);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(MesAgentExecutionService)proxy.getProxy();
    }
    @AfterEach void cleanup(){TenantContextHolder.clear();jdbc.execute("DROP ALL OBJECTS");}
    private Map<String,Object> preview(String kind,String code){return service.prepare(kind,code,null,null,"已核对");}
    private int status(long id){return jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=?",Integer.class,id);}

    @Test void reviewedOrderActionsCallRealServicesAndPersistStates(){
        var confirm=preview("CONFIRM_WORKORDER","WO-DRAFT");assertEquals(0,status(10));
        assertTrue(service.execute(confirm).get("message").toString().contains("已确认"));assertEquals(1,status(10));
        var ordered=inOrder(integrity,orderMapper);ordered.verify(integrity,times(2)).lockBomGraph();ordered.verify(orderMapper).selectByIdForUpdate(10L);
        assertEquals("WO-FINISH",service.execute(preview("FINISH_WORKORDER","WO-FINISH")).get("businessCode"));assertEquals(2,status(20));
        service.execute(preview("CANCEL_WORKORDER","WO-CANCEL"));assertEquals(3,status(30));
        verify(tasks).finishTaskByOrderId(20L);verify(tasks).cancelTaskByOrderId(30L);verify(kitting).releaseForClosedOrder(20L);verify(kitting).releaseForClosedOrder(30L);
    }
    @Test void staleOrForeignRecordsAndRevokedPermissionsCannotExecute(){
        assertThrows(ServiceException.class,()->preview("CONFIRM_WORKORDER","PRIVATE"));
        var payload=preview("CONFIRM_WORKORDER","WO-DRAFT");jdbc.update("UPDATE mes_pro_work_order SET quantity=11 WHERE id=10");
        assertThrows(ServiceException.class,()->service.execute(payload));assertEquals(0,status(10));
        var current=preview("CONFIRM_WORKORDER","WO-DRAFT");when(security.hasPermission("mes:pro-work-order:update")).thenReturn(false);
        assertThrows(ServiceException.class,()->service.execute(current));verifyNoInteractions(orderMapper);assertEquals(0,status(10));
    }
    @Test void underlyingBusinessValidationAndFailedServiceWorkRollBack(){
        var finish=preview("FINISH_WORKORDER","WO-FINISH");
        doThrow(invalid("报工未完成")).when(integrity).validateWorkOrderFinish(20L);
        assertTrue(assertThrows(ServiceException.class,()->service.execute(finish)).getMessage().contains("报工未完成"));assertEquals(1,status(20));verifyNoInteractions(tasks);
        doNothing().when(integrity).validateWorkOrderFinish(20L);
        doAnswer(call->{jdbc.update("UPDATE mes_pro_work_order SET name='不应保存' WHERE id=20");throw invalid("级联失败");}).when(tasks).finishTaskByOrderId(20L);
        assertThrows(ServiceException.class,()->service.execute(finish));assertEquals("可完成工单",jdbc.queryForObject("SELECT name FROM mes_pro_work_order WHERE id=20",String.class));assertEquals(1,status(20));
    }
    @Test void deviceStatusIsPersistedWithoutOverwritingMasterDataAndRepairCannotBeBypassed(){
        var payload=service.prepare("UPDATE_DEVICE_STATUS",null,"DV-001",3,"维护");assertEquals(2,jdbc.queryForObject("SELECT status FROM mes_dv_machinery WHERE id=1",Integer.class));
        service.execute(payload);assertEquals(3,jdbc.queryForObject("SELECT status FROM mes_dv_machinery WHERE id=1",Integer.class));
        assertEquals("保留备注",jdbc.queryForObject("SELECT remark FROM mes_dv_machinery WHERE id=1",String.class));assertEquals("保留品牌",jdbc.queryForObject("SELECT brand FROM mes_dv_machinery WHERE id=1",String.class));
        var resume=service.prepare("UPDATE_DEVICE_STATUS",null,"DV-001",2,"恢复");jdbc.update("INSERT INTO mes_dv_repair VALUES(1,1,1,1,0)");
        assertThrows(ServiceException.class,()->service.execute(resume));assertEquals(3,jdbc.queryForObject("SELECT status FROM mes_dv_machinery WHERE id=1",Integer.class));
        assertThrows(ServiceException.class,()->service.prepare("UPDATE_DEVICE_STATUS",null,"DV-001",99,""));
    }
    private void costBasis(){
        jdbc.update("INSERT INTO mes_cost_workorder(tenant_id,workorder_id,plan_snapshot,plan_quantity) VALUES(1,40,'{\"materialCost\":\"30\",\"laborCost\":\"4\",\"overheadCost\":\"1\",\"totalCost\":\"35\"}',1)");
        jdbc.update("INSERT INTO mes_pro_task VALUES(1,1,40,300,4,0)");
        jdbc.update("INSERT INTO mes_cost_effort(tenant_id,workorder_id,process_id,hours,hourly_rate,overhead_amount,remark,request_id) VALUES(1,40,300,2,2,1,'实际工时','agent-cost')");
        jdbc.update("INSERT INTO mes_wm_transaction VALUES(1,1,40,77,122,92,92,-3,0),(2,1,40,2,123,93,93,1,0)");
        jdbc.update("INSERT INTO mes_wm_product_produce_line VALUES(93,1,2,93,1,0)");
        jdbc.update("INSERT INTO mes_cost_allocation(tenant_id,transaction_id,layer_id,quantity,unit_cost,material_amount,labor_amount,overhead_amount,status) VALUES(1,1,1,3,10,30,0,0,'FINAL')");
        jdbc.update("INSERT INTO mes_cost_stock_layer(tenant_id,material_stock_id,origin_workorder_id,quantity,remaining_quantity,status) VALUES(1,1,40,1,1,'PROVISIONAL')");
    }
    @Test void actualCostSettlementUsesReviewedDecimalAmountsAndSavesFinalLayers(){
        costBasis();var payload=preview("SETTLE_WORKORDER","WO-COST");assertEquals("35.000000",payload.get("actualTotalCost"));
        assertEquals("PROVISIONAL",jdbc.queryForObject("SELECT status FROM mes_cost_workorder",String.class));
        service.execute(payload);assertEquals("SETTLED",jdbc.queryForObject("SELECT status FROM mes_cost_workorder",String.class));
        assertEquals(0,new BigDecimal("35").compareTo(jdbc.queryForObject("SELECT unit_cost FROM mes_cost_stock_layer",BigDecimal.class)));
        assertEquals("FINAL",jdbc.queryForObject("SELECT status FROM mes_cost_stock_layer",String.class));
    }
    @Test void changedAmountsMissingEvidenceAndUnfinishedWorkCannotBeSettled(){
        assertThrows(ServiceException.class,()->preview("SETTLE_WORKORDER","WO-FINISH"));
        assertThrows(ServiceException.class,()->preview("SETTLE_WORKORDER","WO-COST"));
        costBasis();var payload=preview("SETTLE_WORKORDER","WO-COST");jdbc.update("UPDATE mes_cost_effort SET hours=3");
        assertThrows(ServiceException.class,()->service.execute(payload));assertEquals("PROVISIONAL",jdbc.queryForObject("SELECT status FROM mes_cost_workorder",String.class));
        var current=preview("SETTLE_WORKORDER","WO-COST");when(security.hasPermission("mes:cost:settle")).thenReturn(false);assertThrows(ServiceException.class,()->service.execute(current));
    }
}
