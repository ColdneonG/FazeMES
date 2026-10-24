package cn.iocoder.yudao.module.mes.service.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP, no quota service, pricing table or provider fee is involved. */
class MesAssistantUnlimitedCallsTest extends MesAssistantTestDatabase {
    @Test void repeatedCallsAndConnectionTestsWorkWithoutAnyQuotaTables() throws Exception {
        for(String table:List.of("mes_model_usage_policy","mes_model_usage_bucket","mes_model_usage_log","mes_model_usage_event"))jdbc.execute("DROP TABLE "+table);
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();try {
            models.save(new MesAssistantModelGateway.Settings("openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","local-model","fake-test-key",false,.2,128,true),10);
            for(int i=0;i<8;i++)assertEquals("OK",models.complete(List.of(Map.of("role","user","content","ping"))));
            assertEquals(true,models.testConnection().get("connected"));assertEquals(9,calls.get());
            assertFalse(models.status().containsKey("budgetReady"));assertTrue((Boolean)models.status().get("configured"));
        }finally{server.stop(0);}
    }
}
