package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Question;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.service.assistant.MesModelUsageService.Purpose;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesInsightModelPurposeTest {
    private MesAssistantModelGateway gateway;
    private MesInsightService service;
    @BeforeEach void setup(){
        gateway=mock(MesAssistantModelGateway.class);var security=mock(SecurityFrameworkService.class);
        when(security.hasPermission("mes:assistant:chat")).thenReturn(true);
        when(gateway.settings()).thenReturn(Map.of("contextTokens",8192,"maxTokens",1024));
        service=spy(new MesInsightService(mock(JdbcTemplate.class),mock(MesProKittingService.class),mock(MesInsightWorkflow.class),gateway,security,new ObjectMapper(),mock(MesInsightCostQuery.class)));
        doReturn(new LinkedHashMap<>(Map.of("orders",List.of(),"summary",Map.of("open",0,"high",0,"unknown",0)))).when(service).risks();
    }
    @Test void optionalBusinessModelCallUsesItsOwnServerPurpose(){
        when(gateway.complete(eq(Purpose.BUSINESS_INSIGHT),anyList())).thenReturn("依据业务事实生成的答复");
        var result=service.ask(new Question("工单交期风险",true));
        assertEquals("MODEL",result.get("mode"));assertEquals("依据业务事实生成的答复",result.get("answer"));
        verify(gateway).complete(eq(Purpose.BUSINESS_INSIGHT),anyList());verify(gateway,never()).complete(anyList());
    }
    @Test void rulesOnlyBusinessAnalysisDoesNotMakeAModelCall(){
        assertEquals("RULES",service.ask(new Question("工单交期风险",false)).get("mode"));
        verify(gateway,never()).complete(any(Purpose.class),anyList());
    }
}
