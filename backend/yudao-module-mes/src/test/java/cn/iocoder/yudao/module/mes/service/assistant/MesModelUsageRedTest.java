package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** A real local HTTP contract: the second request must never leave the process with a one-call budget. */
class MesModelUsageRedTest extends MesAssistantTestDatabase {
    @Test void aSecondCallCannotReachTheProviderAfterTheDailyCallBudgetIsExhausted() throws Exception {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(10L).setTenantId(1L).setUserType(2),new MockHttpServletRequest());
        String limits="{\"tenantLimits\":{\"dayCalls\":\"1\",\"monthCalls\":\"10\",\"dayTokens\":\"100000\",\"monthTokens\":\"1000000\",\"dayAmount\":null,\"monthAmount\":null,\"maxConcurrency\":2,\"callsPerMinute\":10},\"defaultUserLimits\":{\"dayCalls\":\"1\",\"monthCalls\":\"10\",\"dayTokens\":\"100000\",\"monthTokens\":\"1000000\",\"dayAmount\":null,\"monthAmount\":null,\"maxConcurrency\":2,\"callsPerMinute\":10}}";
        jdbc.update("UPDATE mes_model_usage_policy SET version=2,enabled=TRUE,limits_json=?,rates_json='[]',coverage_start=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE tenant_id=1",limits);
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            models.save(new MesAssistantModelGateway.Settings("openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-model","fake-key",false,.2,128,true),10);
            assertEquals("OK",models.complete(List.of(Map.of("role","user","content","ping"))));
            try { models.complete(List.of(Map.of("role","user","content","ping twice"))); } catch(cn.iocoder.yudao.framework.common.exception.ServiceException expected) { }
            assertEquals(1,calls.get(),"the exhausted quota must prevent a second actual HTTP request");
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log",Integer.class));
        } finally { server.stop(0); }
    }
}
