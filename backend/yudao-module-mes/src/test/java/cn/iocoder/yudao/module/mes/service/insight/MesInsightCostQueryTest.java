package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.*;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Question;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesInsightCostQueryTest {
    private JdbcTemplate jdbc;
    private MesCostService costs;
    private SecurityFrameworkService security;
    private MesInsightCostQuery tool;

    @BeforeEach void setup() {
        jdbc = spy(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:cost_agent_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")));
        for (String table : List.of("mes_md_item", "mes_pro_work_order"))
            jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),deleted INT)");
        jdbc.update("INSERT INTO mes_md_item VALUES(100,1,'BROKY-FG-V2',0),(200,2,'PRIVATE',0),(300,1,'DELETED',1)");
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(10,1,'WO-001',0)");
        costs = mock(MesCostService.class); security = mock(SecurityFrameworkService.class);
        when(security.hasPermission("mes:cost:query")).thenReturn(true);
        tool = new MesInsightCostQuery(jdbc, costs, security);
        TenantContextHolder.setTenantId(1L);
        clearInvocations(jdbc);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); jdbc.execute("DROP ALL OBJECTS"); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result) {
        return (List<Map<String,Object>>) result.get("items");
    }
    @Test void permissionCheckedBeforeAnyBusinessDataAccess() {
        when(security.hasPermission("mes:cost:query")).thenReturn(false);
        assertThrows(ServiceException.class, () -> tool.query("BROKY-FG-V2 成本"));
        verifyNoInteractions(jdbc, costs);
    }
    @Test void tenantDeletedAndSqlFragmentsCannotLeakOtherRecords() {
        var result = tool.query("PRIVATE DELETED NOPE' OR 1=1 -- 成本");
        assertTrue(items(result).isEmpty());
        verifyNoInteractions(costs);
    }
    @Test void productUsesCanonicalEngineWithDecimalPrecisionAndExplicitZero() {
        ProductCost product = new ProductCost(); product.setItemCode("BROKY-FG-V2");
        product.setTotalCost(new BigDecimal("172.000000")); product.setLaborCost(BigDecimal.ZERO);
        product.setSalePrice(new BigDecimal("299.123456"));
        when(costs.product(100L)).thenReturn(product);
        var result = tool.query("broky-fg-v2 的 BOM 标准成本是多少？");
        var row = items(result).get(0);
        assertEquals("172.000000", row.get("totalCost"));
        assertEquals("0", row.get("laborCost"));
        assertEquals("299.123456", row.get("salePrice"));
        assertNull(row.get("materialCost"));
        assertTrue(MesInsightCostQuery.answer(result).contains("标准单位成本 172.000000 元"));
        verify(costs).product(100L); verifyNoMoreInteractions(costs);
    }
    @Test void workorderPlanDoesNotPretendToBeActualCost() {
        WorkorderCost order = new WorkorderCost(); order.setStatus("PROVISIONAL");
        order.setPlanTotalCost(new BigDecimal("17200"));
        order.setIssues(List.of("尚无合格产出"));
        when(costs.workorderCost(10L)).thenReturn(order);
        var result = tool.query("WO-001 实际成本");
        assertNull(items(result).get(0).get("totalCost"));
        assertEquals("17200", items(result).get(0).get("planTotalCost"));
        assertTrue(MesInsightCostQuery.answer(result).contains("实际总成本 待核定"));
        verify(costs).workorderCost(10L); verifyNoMoreInteractions(costs);
    }
    @Test void missingCodeRequestsClarificationInsteadOfGuessing() {
        var result = tool.query("BOM成本是多少？");
        assertTrue(items(result).isEmpty());
        assertTrue(MesInsightCostQuery.answer(result).contains("具体编码"));
        verifyNoInteractions(costs);
    }
    @Test void boundedQueriesRejectMoreThanFiveMatchedRecords() {
        for (int i=1;i<=6;i++) jdbc.update("INSERT INTO mes_md_item VALUES(?,1,?,0)", 400+i, "P-"+i);
        assertThrows(ServiceException.class, () -> tool.query("P-1 P-2 P-3 P-4 P-5 P-6 成本"));
        verifyNoInteractions(costs);
    }
    @Test void businessAssistantRoutesBomToToolWithoutModelOrRiskFallback() {
        var kitting = mock(MesProKittingService.class); var workflow = mock(MesInsightWorkflow.class);
        var model = mock(MesAssistantModelGateway.class);
        var assistant = new MesInsightService(jdbc,kitting,workflow,model,security,new ObjectMapper(),tool);
        var result = assistant.ask(new Question("BOM成本是多少？",false));
        assertEquals("cost", result.get("topic")); assertEquals("RULES", result.get("mode"));
        var missing = assistant.ask(new Question("UNKNOWN-CODE 成本是多少？",true));
        assertEquals("RULES", missing.get("mode"));
        verifyNoInteractions(model,kitting,workflow);
    }
}
