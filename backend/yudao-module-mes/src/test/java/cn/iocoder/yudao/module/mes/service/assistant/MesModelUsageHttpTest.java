package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.vo.MesModelUsageVO.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real bounded localhost HTTP, real persistence and transactions; no commercial provider calls. */
class MesModelUsageHttpTest extends MesAssistantTestDatabase {
    private final List<HttpServer> servers=new ArrayList<>();
    private final AtomicInteger providerCalls=new AtomicInteger();
    private MesModelUsageService meter;
    @BeforeEach void meter(){meter=new MesModelUsageService(jdbc,json,new DataSourceTransactionManager(jdbc.getDataSource()));}
    @AfterEach void servers(){for(var server:servers)server.stop(0);}
    private String server(int status,String body,int delay) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{providerCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();try{if(delay>0)Thread.sleep(delay);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){ }finally{exchange.close();}});
        server.start();servers.add(server);return "http://127.0.0.1:"+server.getAddress().getPort();
    }
    private void config(String base,String provider){models.save(new MesAssistantModelGateway.Settings(provider,base+(provider.equals("openai")?"/v1":""),"test-model","fake-usage-key",false,.2,128,true),10);}
    private Limits limits(String calls,String dayAmount){return new Limits(calls,"1000000","1000000000","10000000000",dayAmount,dayAmount,10,1000);}
    private void policy(String calls,String cap,List<PriceCard> rates){meter.savePolicy(new PolicySaveRequest(((Number)meter.policy().get("version")).intValue(),true,limits(calls,cap),limits(calls,cap),rates,"显式本地HTTP测试预算"));}
    private void priced(){policy("100","10.00000000",List.of(new PriceCard("CHAT","openai","test-model","1","2",null)));}
    private String complete(){return models.complete(List.of(Map.of("role","user","content","private-test-prompt-marker")));}
    @SuppressWarnings("unchecked") private Map<String,Object> row(){return ((List<Map<String,Object>>)meter.page(new UsagePageRequest(null,null,null,null,null,1,100)).get("list")).get(0);}
    @SuppressWarnings("unchecked") private Map<String,Object> day(){return (Map<String,Object>)((Map<String,Object>)meter.summary().get("tenant")).get("day");}
    @SuppressWarnings("unchecked") private Map<String,Object> metric(String name){return (Map<String,Object>)day().get(name);}
    private static String answer(String usage){return "{\"model\":\"served-test\",\"choices\":[{\"message\":{\"content\":\"OK\"}}]"+(usage==null?"":",\"usage\":"+usage)+"}";}
    @Test void reportedUsageUsesSavedPricesAndAllFivePurposesShareCalls() throws Exception {
        config(server(200,answer("{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}"),0),"openai");priced();
        assertEquals("OK",complete());assertEquals("30",row().get("actualTotalTokens"));assertEquals("0.00005000",row().get("calculatedAmount"));assertEquals("30",metric("tokens").get("used"));assertEquals("1",metric("calls").get("used"));assertEquals("0",metric("calls").get("reserved"));
        String audit=jdbc.queryForObject("SELECT state_json FROM mes_model_usage_log",String.class);assertFalse(audit.contains("private-test-prompt-marker"));assertFalse(audit.contains("fake-usage-key"));assertFalse(audit.contains("\"content\""));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"prompt_tokens\":-1,\"completion_tokens\":1,\"total_tokens\":0}","{\"prompt_tokens\":1.5,\"completion_tokens\":1,\"total_tokens\":2.5}","{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":99}","{\"prompt_tokens\":9223372036854775808,\"completion_tokens\":1,\"total_tokens\":9223372036854775809}","{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":1,\"total_tokens\":9223372036854775808}"})
    void malformedOrMissingUsageKeepsTheRealReservation(String usage) throws Exception {
        config(server(200,answer(usage),0),"openai");priced();assertEquals("OK",complete());var row=row();assertNull(row.get("actualInputTokens"));assertNull(row.get("actualOutputTokens"));assertNull(row.get("actualTotalTokens"));assertNull(row.get("calculatedAmount"));assertEquals(row.get("reservedTokens"),row.get("unknownHeldTokens"));assertEquals(row.get("reservedAmount"),row.get("unknownHeldAmount"));assertEquals("1",day().get("unknownChatTokenCalls"));assertNull(day().get("actualTotalTokens"));assertEquals("1",metric("calls").get("used"));assertEquals("0",metric("tokens").get("reserved"));assertEquals(row.get("reservedTokens"),metric("tokens").get("unknown"));
    }
    @Test void genuineZeroUsageIsKnownAndDoesNotLeaveAHeldTokenReservation() throws Exception {
        config(server(200,answer("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"),0),"openai");priced();complete();assertEquals("0",row().get("actualTotalTokens"));assertEquals("0.00000000",row().get("calculatedAmount"));assertNull(row().get("unknownHeldTokens"));assertEquals("0",day().get("unknownTokenCalls"));assertEquals("0",metric("tokens").get("used"));
    }
    @Test void ollamaUsesItsReportedEvaluationCounts() throws Exception {
        config(server(200,"{\"model\":\"local-test\",\"message\":{\"content\":\"local\"},\"prompt_eval_count\":3,\"eval_count\":4}",0),"ollama");assertEquals("local",complete());assertEquals("7",row().get("actualTotalTokens"));assertEquals("7",metric("tokens").get("used"));assertNull(row().get("calculatedAmount"));assertEquals("1",day().get("unpricedCalls"));assertNull(metric("amount").get("unknown"));
    }
    @Test void httpErrorRetainsKnownUsageAndNeverCopiesProviderErrorTextIntoTheAudit() throws Exception {
        config(server(401,"{\"error\":\"private-provider-error fake-usage-key\",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",0),"openai");priced();var error=assertThrows(ServiceException.class,this::complete);assertTrue(error.getMessage().contains("401"));assertFalse(error.getMessage().contains("private-provider-error"));assertEquals("HTTP_ERROR",row().get("outcome"));assertEquals("5",row().get("actualTotalTokens"));assertEquals(401,row().get("httpStatus"));assertFalse(row().toString().contains("private-provider-error"));assertEquals("0.00000700",row().get("calculatedAmount"));
    }
    @ParameterizedTest @ValueSource(strings={"123","true","{}","[]","\"\""})
    void aNonTextualOrBlankResponseCannotBecomeAnAnswerButUsageStillSettles(String content) throws Exception {
        String response="{\"choices\":[{\"message\":{\"content\":"+content+"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
        config(server(200,response,0),"openai");priced();assertThrows(ServiceException.class,this::complete);assertEquals("INVALID_RESPONSE",row().get("outcome"));assertEquals("3",row().get("actualTotalTokens"));assertEquals("3",metric("tokens").get("used"));
    }
    @Test void totalDeadlineEndsARealSentRequestAndRetainsUnknownExposure() throws Exception {
        String base=server(200,answer(null),400);models=new MesAssistantModelGateway(jdbc,json,secrets,meter,base,100);config(base,"openai");priced();long start=System.nanoTime();assertThrows(ServiceException.class,this::complete);assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);assertEquals(1,providerCalls.get());assertEquals("NETWORK_ERROR",row().get("outcome"));assertNull(row().get("actualTotalTokens"));assertEquals("1",metric("calls").get("unknown"));assertEquals(row().get("reservedTokens"),metric("tokens").get("unknown"));assertEquals("0",((Map<?,?>)((Map<?,?>)meter.summary().get("tenant")).get("live")).get("active"));
    }
    @Test void provenPreWriteFailureRefundsExposureAndStillConsumesMinuteAdmission() throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}config("http://127.0.0.1:"+port,"openai");priced();assertThrows(ServiceException.class,this::complete);assertEquals("CANCELLED",row().get("status"));assertEquals("PRE_DISPATCH_ERROR",row().get("outcome"));assertEquals("0",metric("calls").get("used"));assertEquals("0",metric("calls").get("unknown"));assertEquals("0",metric("tokens").get("unknown"));assertEquals("1",((Map<?,?>)((Map<?,?>)meter.summary().get("tenant")).get("minute")).get("admitted"));
    }
    @Test void noPolicyOrMissingPriceCannotSendAnyProviderRequest() throws Exception {
        config(server(200,answer(null),0),"openai");jdbc.update("DELETE FROM mes_model_usage_policy");assertEquals(1_047_140_003,assertThrows(ServiceException.class,this::complete).getCode());assertEquals(0,providerCalls.get());assertEquals("DENIED",row().get("status"));
        meter.savePolicy(new PolicySaveRequest(0,true,limits("100","1"),limits("100","1"),List.of(),"缺价配额拒绝测试"));assertEquals(1_047_140_004,assertThrows(ServiceException.class,this::complete).getCode());assertEquals(0,providerCalls.get());
    }
    @Test void activeBusinessTransactionCannotBeHiddenBySuspensionAndCannotSendHttp() throws Exception {
        config(server(200,answer(null),0),"openai");var transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));transaction.executeWithoutResult(status->assertThrows(ServiceException.class,this::complete));assertEquals(0,providerCalls.get());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log",Integer.class));
    }
    @Test void modelResetAndPurposeSwitchDoNotResetTheSharedDailyCallCounter() throws Exception {
        String base=server(200,answer("{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}"),0);config(base,"openai");policy("1",null,List.of());complete();models.reset();config(base,"openai");assertEquals(1_047_140_004,assertThrows(ServiceException.class,models::testConnection).getCode());assertEquals(1_047_140_004,assertThrows(ServiceException.class,()->models.complete(MesModelUsageService.Purpose.BUSINESS_INSIGHT,List.of(Map.of("role","user","content","switch")))).getCode());assertEquals(1,providerCalls.get());assertEquals("1",metric("calls").get("used"));
    }
    @Test void rawHttpTransportIsNotAPublicBypass(){assertFalse(Arrays.stream(MesAssistantModelGateway.class.getMethods()).anyMatch(method->method.getName().equals("postJson")));}
}
