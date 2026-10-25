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
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MesAgentBusinessToolsTest {
    JdbcTemplate jdbc;SecurityFrameworkService security;MesAgentBusinessTools tools;MesInsightWorkflow workflow;
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
        tools=new MesAgentBusinessTools(jdbc,new ObjectMapper(),security,workflow,mock(MesAssistantModelGateway.class),manager);login(1,10);
    }
    private static void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    private MesAgentBusinessTools.Operation create(String code){return new MesAgentBusinessTools.Operation("CREATE_INCIDENT","真实异常","测试描述",code,"MATERIAL",null,null,10L,null,null,null,"人工确认");}
    private MesAgentBusinessTools.Confirmation confirm(Map<String,Object> preview){return new MesAgentBusinessTools.Confirmation(preview.get("id").toString(),preview.get("token").toString());}
    @SuppressWarnings("unchecked") private Map<String,Object> evidence(Map<String,Object> answer){return (Map<String,Object>)answer.get("evidence");}
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
