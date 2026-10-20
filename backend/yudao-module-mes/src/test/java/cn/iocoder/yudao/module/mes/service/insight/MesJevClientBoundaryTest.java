package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.service.assistant.MesModelUsageService.Purpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesJevClientBoundaryTest {
    @Test void sourceCompatibleConstructorFailsClosedInsteadOfOpeningAnUnmeteredTransport(){
        var client=new MesJevClient(new ObjectMapper());
        assertThrows(ServiceException.class,()->client.evaluate("jev-1.13.0","ignored-key","标题","描述",Map.of()));
    }
    @Test void jevCannotBorrowAChatPurposeOrAcceptAClientSuppliedTransport(){
        var gateway=mock(MesAssistantModelGateway.class);var client=new MesJevClient(new ObjectMapper(),gateway);
        assertThrows(ServiceException.class,()->client.evaluate(Purpose.KNOWLEDGE_CHAT,"jev-1.13.0","标题","描述",Map.of()));
        verifyNoInteractions(gateway);
        for(var method:MesAssistantModelGateway.class.getDeclaredMethods())
            assertFalse(method.getName().equals("postJson")&&Modifier.isStatic(method.getModifiers())&&Modifier.isPublic(method.getModifiers()),"Raw transport cannot remain a public static metering bypass");
    }
    @Test void legacyApiKeyIsNeverPassedToTheMeteredGateway() throws Exception {
        var json=new ObjectMapper();var gateway=mock(MesAssistantModelGateway.class);
        when(gateway.evaluateJev(eq(Purpose.JEV_TRIAGE),any(byte[].class))).thenAnswer(invocation->{
            var request=json.readTree((byte[])invocation.getArgument(1));assertFalse(request.toString().contains("CALLER-KEY-MUST-NOT-LEAK"));
            var answers=new LinkedHashMap<String,Object>();
            for(String field:List.of("category","department")){
                var probabilities=new LinkedHashMap<String,Double>();request.path("questions").path(field).path("criteria").fieldNames().forEachRemaining(k->probabilities.put(k,k.equals("UNKNOWN")?1d:0d));
                answers.put(field,Map.of("type","choice","choice","UNKNOWN","confidence",1d,"probabilities",probabilities));
            }
            return json.writeValueAsBytes(Map.of("model","jev-1.13.0","answers",answers));
        });
        var result=new MesJevClient(json,gateway).evaluate("jev-1.13.0","CALLER-KEY-MUST-NOT-LEAK","标题","描述",Map.of());
        assertEquals("UNKNOWN",result.category());assertNull(result.departmentId());
        verify(gateway,times(1)).evaluateJev(eq(Purpose.JEV_TRIAGE),any(byte[].class));
    }
}
