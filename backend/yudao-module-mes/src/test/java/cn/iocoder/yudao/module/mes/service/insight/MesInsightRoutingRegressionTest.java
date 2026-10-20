package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Question;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.*;

class MesInsightRoutingRegressionTest {
    private MesInsightService legacy() {
        var service = spy(new MesInsightService(mock(JdbcTemplate.class), mock(MesProKittingService.class),
                mock(MesInsightWorkflow.class), mock(MesAssistantModelGateway.class),
                mock(SecurityFrameworkService.class), new ObjectMapper(), mock(MesInsightCostQuery.class)));
        doReturn(Map.of("orders", List.of(), "summary", Map.of("open", 0, "high", 0, "unknown", 0))).when(service).risks();
        return service;
    }
    @Test void stockQuestionMustNotSilentlyReturnUnrelatedWorkorderRisks() {
        assertNotEquals("risks", legacy().ask(new Question("查2026年10月原材料库存台账，按批次", false)).get("topic"));
    }
    @Test void unrelatedQuestionMustNotSilentlyReturnWorkorderRisks() {
        assertNotEquals("risks", legacy().ask(new Question("推荐旅游地点", false)).get("topic"));
    }
}
