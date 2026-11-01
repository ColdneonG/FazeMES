package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.*;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesAgentBusinessController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MesAgentBusinessToolsTest {
    JdbcTemplate jdbc;SecurityFrameworkService security;MesAgentBusinessTools tools;MesInsightWorkflow workflow;
    MesAssistantModelGateway models;MesAgentExecutionService execution;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:agent_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/production_insight.sql"),new ClassPathResource("sql/insight_business.sql"),new FileSystemResource("../../database/migrations/20261024_agent_business_actions.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);
        for(String column:List.of("name VARCHAR(100)","product_id BIGINT","quantity DECIMAL(18,6)","quantity_produced DECIMAL(18,6)","status INT","request_date DATETIME"))jdbc.execute("ALTER TABLE mes_pro_work_order ADD "+column);
        jdbc.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),deleted INT)");
        jdbc.execute("CREATE TABLE mes_dv_machinery(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),status INT,deleted INT)");
        jdbc.update("INSERT INTO system_users VALUES(10,1,'local',0,0),(20,2,'foreign',0,0)");
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,code,name,product_id,quantity,quantity_produced,status,deleted,create_time) VALUES(100,1,'WO-001','own',1,10.000001,2.000001,1,0,CURRENT_TIMESTAMP),(200,2,'PRIVATE','foreign',2,100,100,2,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO mes_md_item VALUES(1,1,'BROKY-FG-V2','产品',0),(2,2,'PRIVATE','foreign',0)");
        jdbc.update("INSERT INTO mes_dv_machinery VALUES(1,1,'DV-001','设备',2,0),(2,2,'PRIVATE','foreign',1,0)");
        security=mock(SecurityFrameworkService.class);when(security.hasPermission(anyString())).thenReturn(true);
        var proxy=new ProxyFactory(new MesInsightWorkflow(jdbc));proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));workflow=(MesInsightWorkflow)proxy.getProxy();
        models=mock(MesAssistantModelGateway.class);execution=mock(MesAgentExecutionService.class);
        tools=new MesAgentBusinessTools(jdbc,new ObjectMapper(),security,workflow,models,manager);
        ReflectionTestUtils.setField(tools,"execution",execution);login(1,10);
    }
    private static void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    private MesAgentBusinessTools.Operation create(String code){return new MesAgentBusinessTools.Operation("CREATE_INCIDENT","真实异常","测试描述",code,"MATERIAL",null,null,10L,null,null,null,"人工确认");}
    private MesAgentBusinessTools.Confirmation confirm(Map<String,Object> preview){return new MesAgentBusinessTools.Confirmation(preview.get("id").toString(),preview.get("token").toString());}
    @SuppressWarnings("unchecked") private Map<String,Object> evidence(Map<String,Object> answer){return (Map<String,Object>)answer.get("evidence");}
    @Test void executionCommandsProduceSpecificProposalsWithoutQueryOrModelFallback(){
        for(boolean useModel:List.of(false,true))for(String[] sample:List.of(
                new String[]{"确认工单 WO-001","CONFIRM_WORKORDER"},new String[]{"取消工单 WO-001","CANCEL_WORKORDER"},
                new String[]{"完成工单 WO-001","FINISH_WORKORDER"},new String[]{"结算工单 WO-001 的实际成本","SETTLE_WORKORDER"},
                new String[]{"把设备 DV-001 的状态改成维护中","UPDATE_DEVICE_STATUS"})){
            var required=new HashSet<String>();var answer=tools.answer(sample[0],useModel,required);
            assertEquals("agent_action",answer.get("topic"),sample[0]);
            var proposal=(MesAgentBusinessTools.Operation)answer.get("proposal");assertEquals(sample[1],proposal.kind());
            if(proposal.kind().equals("UPDATE_DEVICE_STATUS")){assertEquals("DV-001",proposal.deviceCode());assertEquals(3,proposal.deviceStatus());}
            else assertEquals("WO-001",proposal.orderCode());
            assertFalse(required.contains("mes:insight:manage"));assertFalse(answer.containsKey("executionReceipt"));
        }
        verifyNoInteractions(models,execution);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_agent_action",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=100",Integer.class));
    }
    @Test void historicalStatusQueriesStayReadOnlyAndMixedCommandsCannotPartiallyExecute(){
        for(String q:List.of("查询已确认工单列表","查询已取消工单列表","查询未完成工单列表","查看工单 WO-001 是否完成","查看设备维护状态")){
            var answer=tools.answer(q,false,new HashSet<>());assertEquals("agent_data",answer.get("topic"),q);assertFalse(answer.containsKey("proposal"));
        }
        for(String q:List.of("确认工单 WO-001 并完成工单 WO-001","修改工单 WO-001 的数量后确认工单 WO-001","确认工单 WO-001 和 WO-002"))
            assertThrows(ServiceException.class,()->tools.answer(q,true,new HashSet<>()),q);
        verifyNoInteractions(models,execution);
    }
    @Test void domainExecutionUsesOwnedPreviewPersistsReceiptAndRetriesOnlyOnce(){
        var manager=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        var store=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,mock(MesInsightInventoryQuery.class));
        when(security.hasPermission("mes:insight:manage")).thenReturn(false);
        String question="完成工单 WO-001";var required=new HashSet<String>();var answer=tools.answer(question,false,required);
        var saved=store.finish(store.claim(new Question(question,false)),question,Map.of(),new LinkedHashMap<>(),answer,required);
        long turn=((Number)saved.get("turnId")).longValue();var proposal=(MesAgentBusinessTools.Operation)answer.get("proposal");
        when(execution.prepare(eq("FINISH_WORKORDER"),eq("WO-001"),isNull(),isNull(),anyString())).thenReturn(Map.of("kind","FINISH_WORKORDER","workOrderId",100,"orderCode","WO-001"));
        when(execution.execute(anyMap())).thenAnswer(call->{jdbc.update("UPDATE mes_pro_work_order SET status=2 WHERE id=100");return Map.of("businessId",100,"businessCode","WO-001","message","工单 WO-001 已完成。");});
        var preview=tools.prepare(proposal,turn);assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=100",Integer.class));
        var receipt=tools.execute(confirm(preview));assertEquals(2,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=100",Integer.class));
        assertEquals("WO-001",receipt.get("businessCode"));assertEquals(receipt,tools.execute(confirm(preview)));verify(execution,times(1)).execute(anyMap());
        assertTrue(jdbc.queryForObject("SELECT answer_json FROM mes_insight_turn WHERE id=?",String.class,turn).contains("executionReceipt"));
        assertThrows(ServiceException.class,()->tools.prepare(proposal,turn));
        when(security.hasPermission("mes:pro-work-order:update")).thenReturn(false);assertThrows(ServiceException.class,()->tools.execute(confirm(preview)));
    }
    @Test void failedDomainExecutionRollsBackBusinessAndNeverStoresSuccess(){
        var proposal=(MesAgentBusinessTools.Operation)tools.answer("完成工单 WO-001",false,new HashSet<>()).get("proposal");
        when(execution.prepare(anyString(),anyString(),isNull(),isNull(),anyString())).thenReturn(Map.of("kind","FINISH_WORKORDER","workOrderId",100));
        var preview=tools.prepare(proposal);
        when(execution.execute(anyMap())).thenAnswer(call->{jdbc.update("UPDATE mes_pro_work_order SET status=2 WHERE id=100");throw new IllegalStateException("business failure");});
        assertThrows(IllegalStateException.class,()->tools.execute(confirm(preview)));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_pro_work_order WHERE id=100",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM mes_agent_action",String.class));
        assertNull(jdbc.queryForObject("SELECT result_json FROM mes_agent_action",String.class));
    }
    @Test void reportReadsActualTenantDataAndRequiresExportPermission(){
        var required=new HashSet<String>();var answer=tools.answer("生成全部工单报表",false,required);var rows=(List<?>)evidence(answer).get("list");
        assertEquals(1,rows.size());assertTrue(rows.toString().contains("10.000001"));assertFalse(rows.toString().contains("PRIVATE"));assertTrue(required.contains("mes:pro-work-order:export"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_agent_action",Integer.class));
        when(security.hasPermission("mes:pro-work-order:export")).thenReturn(false);assertThrows(ServiceException.class,()->tools.answer("生成工单报表",false,new HashSet<>()));
    }
    @Test void dateFiltersAreExplicitAndUnknownCodesNeverReturnUnfilteredData(){
        assertEquals(0,evidence(tools.answer("生成2020年1月工单报表",false,new HashSet<>())).get("total"));
        assertEquals(0,evidence(tools.answer("查询PRIVATE工单列表",false,new HashSet<>())).get("total"));
        assertThrows(ServiceException.class,()->tools.answer("生成10月工单报表",false,new HashSet<>()));
        assertThrows(ServiceException.class,()->tools.answer("生成2026-02-30工单报表",false,new HashSet<>()));
    }
    @Test void deviceStatusUsesRealModuleEnumeration(){
        var answer=tools.answer("查询设备状态",false,new HashSet<>());assertEquals(1,evidence(answer).get("total"));assertTrue(evidence(answer).get("list").toString().contains("生产中"));
    }
    @Test void proposalsAndPreviewsDoNotExecuteAndConfirmationIsIdempotent(){
        assertNotNull(tools.answer("新建异常：缺料需要跟进",false,new HashSet<>()).get("proposal"));
        var preview=tools.prepare(create("WO-001"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
        var first=tools.execute(confirm(preview));assertEquals(first,tools.execute(confirm(preview)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident_event",Integer.class));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mes_agent_action",String.class));
    }
    @Test void foreignRecordsTokensExpiryAndRevokedPermissionsCannotExecute(){
        assertThrows(ServiceException.class,()->tools.prepare(create("PRIVATE")));
        var preview=tools.prepare(create("WO-001"));var confirmation=confirm(preview);
        assertThrows(ServiceException.class,()->tools.execute(new MesAgentBusinessTools.Confirmation(confirmation.id(),"wrong")));
        login(2,20);assertThrows(ServiceException.class,()->tools.execute(confirmation));login(1,11);assertThrows(ServiceException.class,()->tools.execute(confirmation));login(1,10);
        when(security.hasPermission("mes:insight:manage")).thenReturn(false);assertThrows(ServiceException.class,()->tools.execute(confirmation));when(security.hasPermission("mes:insight:manage")).thenReturn(true);
        jdbc.update("UPDATE mes_agent_action SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");assertThrows(ServiceException.class,()->tools.execute(confirmation));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
    }
    @Test void confirmationUsesNormalWorkflowAndRejectsStaleVersions(){
        long id=((Number)tools.execute(confirm(tools.prepare(create("WO-001")))).get("businessId")).longValue();
        var operation=new MesAgentBusinessTools.Operation("MOVE_INCIDENT",null,null,null,null,id,"IN_PROGRESS",10L,"","","","开始处理");
        var preview=tools.prepare(operation);jdbc.update("UPDATE mes_insight_incident SET version=version+1 WHERE id=?",id);
        assertThrows(ServiceException.class,()->tools.execute(confirm(preview)));assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM mes_insight_incident",String.class));
        var result=tools.execute(confirm(tools.prepare(operation)));assertEquals(id,((Number)result.get("businessId")).longValue());assertEquals("IN_PROGRESS",jdbc.queryForObject("SELECT status FROM mes_insight_incident",String.class));
        var close=new MesAgentBusinessTools.Operation("MOVE_INCIDENT",null,null,null,null,id,"CLOSED",10L,"","","","关闭");
        assertThrows(ServiceException.class,()->tools.prepare(close));
    }
    @Test void concurrentRetriesCreateOnlyOneBusinessRecord() throws Exception {
        var confirmation=confirm(tools.prepare(create("WO-001")));var pool=Executors.newFixedThreadPool(2);
        try{Callable<Map<String,Object>> task=()->{login(1,10);try{return tools.execute(confirmation);}finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}};
            var first=pool.submit(task);var second=pool.submit(task);assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void conversationBoundActionsSupersedeOldPreviewsAndPersistReceipt(){
        var manager=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        var store=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,mock(MesInsightInventoryQuery.class));
        String question="新建异常：缺料需要跟进";
        var claim=store.claim(new Question(question,false));var required=new HashSet<String>();
        var saved=store.finish(claim,question,Map.of(),new LinkedHashMap<>(),tools.answer(question,false,required),required);
        long turn=((Number)saved.get("turnId")).longValue();
        var old=tools.prepare(create("WO-001"),turn);var current=tools.prepare(create("WO-001"),turn);
        assertThrows(ServiceException.class,()->tools.execute(confirm(old)));
        var receipt=tools.execute(confirm(current));assertEquals(receipt,tools.execute(confirm(current)));
        assertTrue(jdbc.queryForObject("SELECT answer_json FROM mes_insight_turn WHERE id=?",String.class,turn).contains("executionReceipt"));
        assertThrows(ServiceException.class,()->tools.prepare(create("WO-001"),turn));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
    }
    @Test void exportUsesOwnedConversationSnapshotAndRejectsOtherOwners() throws Exception {
        var inventory=mock(MesInsightInventoryQuery.class);var manager=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));var store=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,inventory);
        var claim=store.claim(new Question("生成工单报表",false));var required=new HashSet<String>();var answer=tools.answer("生成工单报表",false,required);
        var saved=store.finish(claim,"生成工单报表",Map.of(),new LinkedHashMap<>(),answer,required);long turn=((Number)saved.get("turnId")).longValue();
        var response=new MockHttpServletResponse();new MesAgentBusinessController(tools,store).report(turn,response);assertTrue(response.getContentAsByteArray().length>1000);assertEquals((byte)'P',response.getContentAsByteArray()[0]);assertEquals((byte)'K',response.getContentAsByteArray()[1]);
        login(1,11);assertThrows(ServiceException.class,()->store.artifact(turn));login(2,20);assertThrows(ServiceException.class,()->store.artifact(turn));
    }
}
