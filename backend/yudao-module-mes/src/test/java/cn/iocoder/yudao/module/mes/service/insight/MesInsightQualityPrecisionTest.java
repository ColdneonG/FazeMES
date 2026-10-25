package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController;
import cn.iocoder.yudao.module.mes.framework.web.MesDecimalResponseAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MesInsightQualityPrecisionTest {
    private JdbcTemplate jdbc;
    private MesInsightService service;

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(71L);
        jdbc = mock(JdbcTemplate.class);
        service = new MesInsightService(jdbc, null, null, null, null, new ObjectMapper(), null);
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private Map<String, Object> report(long id, long station, Object quantity, Object rejected) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("code", "FB-" + id); row.put("itemId", 10L);
        row.put("workstationId", station); row.put("station", "Station " + station);
        row.put("processId", 20L); row.put("process", "Process"); row.put("product", "Product");
        row.put("batch", "BATCH"); row.put("sop", "V1");
        row.put("quantity", quantity); row.put("rejected", rejected);
        return row;
    }

    private void rows(List<Map<String, Object>> reports) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            assertTrue(invocation.<String>getArgument(0).contains("f.tenant_id=?"));
            assertEquals(71L, ((Object[]) invocation.getRawArguments()[1])[0]);
            return reports;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groups(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("groups");
    }

    @Test
    void groupedTotalsPreserveMicroAmountsBesideMaximumQuantities() {
        rows(List.of(report(1, 1, new BigDecimal("99999999999999.999998"), new BigDecimal("99999999999999.999997")),
                report(2, 1, new BigDecimal("0.000001"), new BigDecimal("0.000001")),
                report(3, 2, BigDecimal.ONE, BigDecimal.ZERO)));
        var result = service.quality(30, 30);
        var station = groups(result).stream().filter(g -> g.get("dimension").equals("工作站")
                && g.get("value").equals("Station 1")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("99999999999999.999999"), station.get("quantity"));
        assertEquals(new BigDecimal("99999999999999.999998"), station.get("rejected"));
        var product = groups(result).stream().filter(g -> g.get("dimension").equals("产品")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("100000000000000.999999"), product.get("quantity"));
        assertInstanceOf(Double.class, station.get("rate"));
        assertInstanceOf(Double.class, station.get("baselineRate"));
        assertInstanceOf(Double.class, station.get("difference"));
    }

    @Test
    void invalidRejectedMicroAmountIsExcludedBeforeRoundedComparisons() {
        rows(List.of(report(1, 1, new BigDecimal("99999999999999.999998"), new BigDecimal("99999999999999.999999")),
                report(2, 1, 1L, 0), report(3, 1, Double.NaN, 0),
                report(4, 1, Double.POSITIVE_INFINITY, 0)));
        var result = service.quality(30, 1);
        assertEquals(1, result.get("records"));
        assertEquals(3, result.get("excluded"));
        assertEquals(BigDecimal.ONE, groups(result).get(0).get("quantity"));
    }

    @Test
    void qualityHttpContractUsesStringsForExactTotalsAndNumbersForPercentages() throws Exception {
        rows(List.of(report(1, 1, new BigDecimal("99999999999999.999999"), new BigDecimal("0.000001")),
                report(2, 1, new BigDecimal("0.000001"), BigDecimal.ZERO)));
        var mapper = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(new MesInsightController(service, null, null))
                .setControllerAdvice(new MesDecimalResponseAdvice(mapper), new GlobalResponseBodyHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(get("/mes/insight/quality").param("days", "30").param("minSample", "30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups[0].quantity").value("100000000000000.000000"))
                .andExpect(jsonPath("$.data.groups[0].rejected").value("0.000001"))
                .andExpect(jsonPath("$.data.groups[0].rate").isNumber())
                .andExpect(jsonPath("$.data.groups[0].difference").isNumber());
    }
}
