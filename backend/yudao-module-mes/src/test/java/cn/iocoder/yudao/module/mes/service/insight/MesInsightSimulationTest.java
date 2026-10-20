package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Simulation;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real snapshot -> three plans -> ISO response and cost calculation. */
class MesInsightSimulationTest {
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 10, 19, 8, 0);
    private static final long TENANT = 67L;
    private JdbcTemplate jdbc;
    private MesProKittingService kitting;
    private MesInsightWorkflow workflow;
    private MesAssistantModelGateway model;
    private SecurityFrameworkService security;
    private MesInsightCostQuery costQuery;
    private MesInsightService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT);
        jdbc = mock(JdbcTemplate.class);
        kitting = mock(MesProKittingService.class);
        workflow = mock(MesInsightWorkflow.class);
        model = mock(MesAssistantModelGateway.class);
        security = mock(SecurityFrameworkService.class);
        costQuery = mock(MesInsightCostQuery.class);
        service = new MesInsightService(jdbc, kitting, workflow, model, security, new ObjectMapper(), costQuery);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void ordinaryOvernightWorkNeverChargesForIdleEveningInAnyScenario() {
        var response = simulate(1L, 8, List.of(order(1L)), List.of(task(11L, 1L, 101L, 1, 9)));

        assertEquals("2026-10-19T08:00:00", response.get("generatedAt"));
        assertEquals(false, response.get("manualEstimate"));
        assertEquals(List.of(), response.get("warnings"));
        for (String key : List.of("BASELINE", "PRIORITY", "OVERTIME")) {
            var scenario = scenario(response, key);
            assertEquals("2026-10-20T09:00:00", scenario.get("selectedFinish"), key);
            assertCharge(scenario, 0, 0);
            assertEquals("2026-10-19T08:00:00", slots(scenario).get(0).get("startAt"));
            assertEquals("2026-10-20T09:00:00", slots(scenario).get(0).get("endAt"));
        }
    }

    @Test
    void extendedTenHourDayChargesOnlyTwoHoursEvenWhenTaskContinuesNextDay() {
        var response = simulate(1L, 10, List.of(order(1L)), List.of(task(11L, 1L, 101L, 1, 11)));

        for (String key : List.of("BASELINE", "PRIORITY")) {
            var scenario = scenario(response, key);
            assertEquals("2026-10-20T11:00:00", scenario.get("selectedFinish"));
            assertCharge(scenario, 0, 0);
        }
        var extended = scenario(response, "OVERTIME");
        assertEquals("2026-10-20T09:00:00", extended.get("selectedFinish"));
        assertEquals(10, extended.get("dailyHours"));
        assertCharge(extended, 2, 160);
        assertEquals(-2.0, number(impacts(extended).get(0), "shiftHours"));
    }

    @Test
    void chargesAllAffectedWorkstationsRatherThanOnlySelectedOrder() {
        var response = simulate(1L, 10, List.of(order(1L), order(2L)),
                List.of(task(11L, 1L, 101L, 1, 10), task(21L, 2L, 102L, 1, 10)));

        assertCharge(scenario(response, "BASELINE"), 0, 0);
        assertCharge(scenario(response, "PRIORITY"), 0, 0);
        var extended = scenario(response, "OVERTIME");
        assertEquals("2026-10-19T18:00:00", extended.get("selectedFinish"));
        assertCharge(extended, 4, 320);
        assertEquals(2, slots(extended).size());
        for (var slot : slots(extended)) {
            assertEquals("2026-10-19T08:00:00", slot.get("startAt"));
            assertEquals("2026-10-19T18:00:00", slot.get("endAt"));
        }
        assertEquals(2, impacts(extended).size());
        assertEquals("2026-10-19T18:00:00", impact(extended, 2L).get("forecastAt"));
        assertEquals(false, impact(extended, 2L).get("selected"));
    }

    @Test
    void priorityChangesSharedResourceQueueButPreservesEachOrdersProcessSequence() {
        var response = simulate(2L, 8, List.of(order(1L), order(2L)), List.of(
                task(10L, 1L, 101L, 1, 6), task(11L, 1L, 102L, 2, 3),
                task(20L, 2L, 101L, 1, 4), task(21L, 2L, 102L, 2, 2)));

        var baseline = scenario(response, "BASELINE");
        assertEquals("2026-10-20T12:00:00", baseline.get("selectedFinish"));
        assertEquals("2026-10-20T09:00:00", impact(baseline, 1L).get("forecastAt"));
        for (String key : List.of("PRIORITY", "OVERTIME")) {
            var priority = scenario(response, key);
            assertEquals("2026-10-19T14:00:00", priority.get("selectedFinish"));
            assertEquals("2026-10-20T13:00:00", impact(priority, 1L).get("forecastAt"));
            assertEquals("2026-10-19T08:00:00", slot(priority, 20L).get("startAt"));
            assertEquals("2026-10-19T12:00:00", slot(priority, 20L).get("endAt"));
            assertEquals(slot(priority, 20L).get("endAt"), slot(priority, 21L).get("startAt"));
            assertEquals(slot(priority, 10L).get("endAt"), slot(priority, 11L).get("startAt"));
            assertCharge(priority, 0, 0);
        }
        assertCharge(baseline, 0, 0);
    }

    private Map<String, Object> simulate(long selectedOrder, int dailyHours,
                                         List<Map<String, Object>> orders, List<Map<String, Object>> tasks) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("tenant_id=?"));
            assertTrue(sql.contains("deleted=0"));
            assertArrayEquals(new Object[]{TENANT}, (Object[]) invocation.getRawArguments()[1]);
            if (sql.startsWith("SELECT w.id")) return mutableRows(orders);
            if (sql.startsWith("SELECT t.id")) return mutableRows(tasks);
            fail("Unexpected query during read-only simulation: " + sql);
            return List.of();
        });
        Map<String, Object> response;
        try (MockedStatic<LocalDateTime> clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(MONDAY);
            response = service.simulate(new Simulation(selectedOrder, dailyHours, 80, null, 0));
        }
        verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(kitting, workflow, model, security, costQuery);
        assertEquals(List.of("BASELINE", "PRIORITY", "OVERTIME"),
                scenarios(response).stream().map(row -> row.get("key")).toList());
        return response;
    }

    private static Map<String, Object> order(long id) {
        return row("id", id, "code", "WO-" + id, "name", "Order " + id, "productId", 1000L + id,
                "quantity", 1, "producedQuantity", 0, "status", 1,
                "dueAt", MONDAY.plusDays(4).withHour(16), "productName", "Product " + id);
    }

    private static Map<String, Object> task(long id, long orderId, long stationId, int sequence, double hours) {
        return row("id", id, "code", "TASK-" + id, "name", "Process " + sequence,
                "workOrderId", orderId, "workstationId", stationId, "processId", (long) sequence,
                "startTime", MONDAY, "endTime", MONDAY.plusHours(1), "duration", hours / 8,
                "quantity", 1, "producedQuantity", 0, "changedQuantity", 0,
                "stationName", "Station " + stationId, "processName", "Process " + sequence,
                "sequence", sequence, "dueAt", MONDAY.plusDays(4).withHour(16));
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static List<Map<String, Object>> mutableRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        rows.forEach(row -> result.add(new LinkedHashMap<>(row)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scenarios(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("scenarios");
    }

    private static Map<String, Object> scenario(Map<String, Object> response, String key) {
        return scenarios(response).stream().filter(row -> key.equals(row.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slots(Map<String, Object> scenario) {
        return (List<Map<String, Object>>) scenario.get("slots");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> impacts(Map<String, Object> scenario) {
        return (List<Map<String, Object>>) scenario.get("impacts");
    }

    private static Map<String, Object> impact(Map<String, Object> scenario, long id) {
        return impacts(scenario).stream().filter(row -> ((Number) row.get("id")).longValue() == id)
                .findFirst().orElseThrow();
    }

    private static Map<String, Object> slot(Map<String, Object> scenario, long taskId) {
        return slots(scenario).stream().filter(row -> ((Number) row.get("taskId")).longValue() == taskId)
                .findFirst().orElseThrow();
    }

    private static double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }

    private static void assertCharge(Map<String, Object> scenario, double hours, double cost) {
        assertEquals(hours, number(scenario, "overtimeHours"), 0.0001, scenario.get("key").toString());
        assertEquals(cost, number(scenario, "addedCost"), 0.0001, scenario.get("key").toString());
    }
}
