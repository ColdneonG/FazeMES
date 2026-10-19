package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.IncidentCreate;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantSecretStore;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MesJevTriageTest {
    @TempDir Path temporary;
    JdbcTemplate jdbc; MesJevTriageService service; MesInsightWorkflow workflow;
    MesAssistantSecretStore secrets; ObjectMapper json=new ObjectMapper();
    JsonNode captured; String category="QUALITY",department="D_1"; boolean malformed=false;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:jev_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/production_insight.sql")).execute(source);
        jdbc=new JdbcTemplate(source);
        secrets=new MesAssistantSecretStore(new MockEnvironment().withProperty("mes.assistant.master-key-file",temporary.resolve("private/master.key").toString()));
        var client=new MesJevClient(json){
            @Override protected byte[] post(byte[] body,String key)throws Exception{
                assertEquals("test-only-jev-key",key);captured=json.readTree(body);
                var answers=new LinkedHashMap<String,Object>();
                for(String name:List.of("category","department")){
                    var criteria=captured.path("questions").path(name).path("criteria");
                    String selected=name.equals("category")?category:department;
                    Map<String,Double> probabilities=new LinkedHashMap<>();criteria.fieldNames().forEachRemaining(k->probabilities.put(k,k.equals(selected)?1d:0d));
                    answers.put(name,Map.of("type","choice","choice",selected,"probabilities",probabilities,"confidence",.8));
                }
                return json.writeValueAsBytes(Map.of("model","jev-1.13.0","answers",malformed?Map.of():answers));
            }
        };
        service=new MesJevTriageService(jdbc,secrets,client);
        var proxy=new ProxyFactory(new MesInsightWorkflow(jdbc));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        workflow=(MesInsightWorkflow)proxy.getProxy();login(1,10);
        jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,status,deleted) VALUES(10,1,'local',0,0),(20,2,'foreign',0,0)");
        jdbc.update("INSERT INTO system_dept(id,tenant_id,name,status,deleted) VALUES(1,1,'质量部门',0,0),(2,2,'外租户部门',0,0),(3,1,'停用部门',1,0),(4,1,'删除部门',0,1)");
    }
    void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    @AfterEach void clear(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    void enable(){service.save(new MesJevTriageService.Settings("jev-1.13.0","test-only-jev-key",false,true));}
    Map<String,Object> suggest(){return service.suggest("尺寸异常","连续出现尺寸偏差");}
    IncidentCreate request(Long advice,Long dept,String title){return new IncidentCreate(title,"QUALITY",null,null,null,null,null,"连续出现尺寸偏差",dept,advice);}
    @Test void credentialsAreIndependentEncryptedTenantBoundAndNeverReturned(){
        assertEquals(false,service.settings().get("configured"));
        assertEquals(0L,((Number)((Map<?,?>)service.status().get("stats")).get("generated")).longValue());
        assertThrows(ServiceException.class,()->service.save(new MesJevTriageService.Settings("jev-latest","",false,true)));
        enable();var first=service.settings();assertEquals(true,first.get("configured"));assertFalse(first.containsKey("apiKey"));
        String encrypted=jdbc.queryForObject("SELECT secret_ciphertext FROM mes_insight_jev_config WHERE tenant_id=1",String.class);
        assertFalse(encrypted.contains("test-only-jev-key"));assertEquals("test-only-jev-key",secrets.decrypt(encrypted,1));
        assertFalse(new MesJevTriageService.Settings("jev-latest","test-only-jev-key",false,true).toString().contains("test-only-jev-key"));
        service.save(new MesJevTriageService.Settings("jev-1.13.0","",false,true));assertEquals(first,service.settings());
        login(2,20);assertEquals(false,service.settings().get("configured"));assertThrows(ServiceException.class,()->secrets.decrypt(encrypted,2));
        login(1,10);service.save(new MesJevTriageService.Settings("jev-1.13.0","",true,false));assertEquals(false,service.settings().get("apiKeyConfigured"));
    }
    @Test void protocolUsesTwoAtomicChoicesOnlyExistingTenantDepartmentsAndNoAutomaticIncident(){
        enable();var result=suggest();assertEquals("QUALITY",result.get("category"));assertEquals(1L,result.get("departmentId"));
        assertEquals("jev-1.13.0",captured.path("model").asText());assertEquals(2,captured.path("questions").size());
        var choices=captured.path("questions").path("department").path("criteria");assertTrue(choices.has("D_1"));assertTrue(choices.has("UNKNOWN"));assertEquals(2,choices.size());
        assertFalse(captured.toString().contains("test-only-jev-key"));assertFalse(captured.has("messages"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
        service.test();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
    }
    @Test void humanConfirmationPersistsDepartmentAuditAndFeedbackAndCannotConsumeAdviceTwice(){
        enable();long advice=((Number)suggest().get("id")).longValue();long id=workflow.create(request(advice,1L,"尺寸异常"));
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM mes_insight_incident WHERE id=?",String.class,id));
        assertNull(jdbc.queryForObject("SELECT owner_id FROM mes_insight_incident WHERE id=?",Long.class,id));
        assertEquals(1L,jdbc.queryForObject("SELECT department_id FROM mes_insight_incident_assignment WHERE incident_id=?",Long.class,id));
        assertTrue(jdbc.queryForObject("SELECT note FROM mes_insight_incident_event WHERE incident_id=?",String.class,id).contains("人工确认Jev"));
        var stats=(Map<?,?>)service.status().get("stats");assertEquals(1L,((Number)stats.get("reviewed")).longValue());assertEquals(1L,((Number)stats.get("categoryAgreed")).longValue());
        assertThrows(ServiceException.class,()->workflow.create(request(advice,1L,"尺寸异常")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
        var listed=(List<?>)workflow.list().get("cases");assertTrue(((Map<?,?>)listed.get(0)).containsValue("质量部门"));
    }
    @Test void staleForeignReusedAndChangedInputAdviceCannotBeConfirmedAndManualPathSurvives(){
        enable();long advice=((Number)suggest().get("id")).longValue();
        assertThrows(ServiceException.class,()->workflow.create(request(advice,1L,"修改后的标题")));
        login(1,11);assertThrows(ServiceException.class,()->workflow.create(request(advice,1L,"尺寸异常")));
        login(2,20);assertThrows(ServiceException.class,()->workflow.create(request(advice,null,"尺寸异常")));
        login(1,10);for(long dep:List.of(2L,3L,4L)) assertThrows(ServiceException.class,()->workflow.create(request(advice,dep,"尺寸异常")));
        jdbc.update("UPDATE mes_insight_jev_triage SET create_time=DATEADD('HOUR',-25,CURRENT_TIMESTAMP) WHERE id=?",advice);
        assertThrows(ServiceException.class,()->workflow.create(request(advice,1L,"尺寸异常")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
        assertNotNull(workflow.create(request(null,1L,"手动建立")));
    }
    @Test void unknownIsPreservedAndInvalidCloudResponsesNeverCreateSuggestionRows(){
        assertThrows(ServiceException.class,this::suggest);enable();category="UNKNOWN";department="UNKNOWN";
        var result=suggest();assertEquals("UNKNOWN",result.get("category"));assertNull(result.get("departmentId"));
        malformed=true;assertThrows(ServiceException.class,this::suggest);malformed=false;category="INVENTED";
        assertThrows(ServiceException.class,this::suggest);category="QUALITY";department="D_2";
        assertThrows(ServiceException.class,this::suggest);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
    }
}
