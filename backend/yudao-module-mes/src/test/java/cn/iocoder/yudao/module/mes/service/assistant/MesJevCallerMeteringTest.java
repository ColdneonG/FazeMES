package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.vo.MesModelUsageVO.*;
import cn.iocoder.yudao.module.mes.service.insight.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real caller -> metering -> bounded local HTTP -> independent settlement; never contacts a paid provider. */
class MesJevCallerMeteringTest extends MesAssistantTestDatabase {
    private HttpServer server;
    private ExecutorService workers;
    private MesJevTriageService triage;
    private MesModelUsageService usage;
    private DataSourceTransactionManager manager;
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    private volatile boolean invalidProtocol;
    @BeforeEach void caller(){
        jdbc.execute("CREATE TABLE mes_insight_jev_config(tenant_id BIGINT PRIMARY KEY,model_name VARCHAR(80),secret_ciphertext CLOB,enabled BOOLEAN,updater BIGINT)");
        jdbc.execute("CREATE TABLE mes_insight_jev_triage(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,creator BIGINT,input_hash VARCHAR(64),model_name VARCHAR(80),category VARCHAR(30),department_id BIGINT,category_confidence DOUBLE,department_confidence DOUBLE,incident_id BIGINT,final_category VARCHAR(30),final_department_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE system_dept(id BIGINT PRIMARY KEY,tenant_id BIGINT,name VARCHAR(100),status INT,deleted INT,sort INT)");
        jdbc.update("INSERT INTO system_dept VALUES(1,1,'质量部门',0,0,1)");
        try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(Exception ex){throw new IllegalStateException(ex);}
        workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/v1/systemone",exchange->{
            try{
                requests.incrementAndGet();assertEquals("Bearer SERVER-CONFIGURED-KEY",exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request=json.readTree(exchange.getRequestBody());var answers=new LinkedHashMap<String,Object>();
                for(String name:List.of("category","department")){
                    String chosen=name.equals("category")?"QUALITY":"D_1";
                    var probabilities=new LinkedHashMap<String,Double>();request.path("questions").path(name).path("criteria").fieldNames().forEachRemaining(key->probabilities.put(key,key.equals(chosen)?1d:0d));
                    answers.put(name,Map.of("type","choice","choice",chosen,"confidence",1d,"probabilities",probabilities));
                }
                byte[] response=json.writeValueAsBytes(Map.of("model","jev-1.13.0","answers",invalidProtocol?Map.of():answers));
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable failure){serverFailure.set(failure);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){ }}finally{exchange.close();}
        });server.start();
        manager=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));usage=new MesModelUsageService(jdbc,json,manager);
        var gateway=new MesAssistantModelGateway(jdbc,json,secrets,usage,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1",5000);
        var target=new MesJevTriageService(jdbc,secrets,new MesJevClient(json,gateway));var factory=new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));triage=(MesJevTriageService)factory.getProxy();
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(triage));
        triage.save(new MesJevTriageService.Settings("jev-1.13.0","SERVER-CONFIGURED-KEY",false,true));
    }
    @AfterEach void stopCaller() throws Exception {if(server!=null)server.stop(0);if(workers!=null){workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}assertNull(serverFailure.get());}
    private Map<String,Object> suggest(){return triage.suggest("尺寸异常","连续出现尺寸偏差");}
    private List<JsonNode> audit(){return jdbc.query("SELECT state_json FROM mes_model_usage_log ORDER BY created_at,id",(rs,i)->{try{return json.readTree(rs.getString(1));}catch(Exception ex){throw new IllegalStateException(ex);}});}
    @Test void bothJevPurposesAreActuallyMeteredAndUnknownTokensStayNull(){
        assertEquals("QUALITY",suggest().get("category"));assertEquals(true,triage.test().get("connected"));assertEquals(2,requests.get());
        var rows=audit();assertEquals(2,rows.size());assertEquals(Set.of("JEV_TRIAGE","JEV_CONNECTION_TEST"),rows.stream().map(r->r.path("purpose").asText()).collect(java.util.stream.Collectors.toSet()));
        for(var row:rows){assertEquals("SETTLED",row.path("status").asText());assertEquals("SUCCESS",row.path("outcome").asText());assertEquals(200,row.path("httpStatus").asInt());assertTrue(row.path("actualTotalTokens").isNull());assertTrue(row.path("accountedTokens").isNull());}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
        assertEquals(2L,jdbc.queryForObject("SELECT used_calls FROM mes_model_usage_bucket WHERE subject_type='TENANT' AND window_type='DAY'",Long.class));
        var day=(Map<?,?>)((Map<?,?>)usage.summary().get("tenant")).get("day");assertEquals("2",day.get("unknownJevTokenCalls"));assertNull(day.get("actualTotalTokens"));
        String evidence=jdbc.queryForList("SELECT state_json FROM mes_model_usage_event",String.class).toString()+rows;
        assertFalse(evidence.contains("SERVER-CONFIGURED-KEY"));assertFalse(evidence.contains("连续出现尺寸偏差"));
    }
    @Test void aProtocolInvalidHttp200IsAuditedBeforeTheCallerRejectsAdvice(){
        invalidProtocol=true;assertThrows(ServiceException.class,this::suggest);assertEquals(1,requests.get());
        var row=audit().get(0);assertEquals("SETTLED",row.path("status").asText());assertEquals("INVALID_RESPONSE",row.path("outcome").asText());assertEquals(200,row.path("httpStatus").asInt());
        assertTrue(row.path("actualTotalTokens").isNull());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
    }
    @Test void connectionTestAndAdviceShareTheSameCallQuotaBeforeAnySecondHttp(){
        var limits=new Limits("1","10","100000","1000000",null,null,4,100);
        usage.savePolicy(new PolicySaveRequest(1,true,limits,limits,List.of(),"本测试显式限制两条Jev路径共享一次调用"));
        suggest();assertThrows(ServiceException.class,triage::test);assertEquals(1,requests.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log WHERE status='DENIED' AND purpose='JEV_CONNECTION_TEST'",Integer.class));
    }
    @Test void advicePersistenceFailureCannotRollbackAnAlreadyChargedProviderCall(){
        jdbc.execute("ALTER TABLE mes_insight_jev_triage ADD CONSTRAINT reject_advice CHECK(category='NEVER')");
        assertThrows(RuntimeException.class,this::suggest);assertEquals(1,requests.get());assertEquals("SETTLED",audit().get(0).path("status").asText());
        assertEquals(1L,jdbc.queryForObject("SELECT used_calls FROM mes_model_usage_bucket WHERE subject_type='TENANT' AND window_type='DAY'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_jev_triage",Integer.class));
    }
    @Test void anActiveBusinessTransactionCannotBeSuspendedAcrossJevHttp(){
        new TransactionTemplate(manager).executeWithoutResult(status->{assertThrows(ServiceException.class,this::suggest);status.setRollbackOnly();});
        assertEquals(0,requests.get());assertTrue(audit().isEmpty());
    }
}
