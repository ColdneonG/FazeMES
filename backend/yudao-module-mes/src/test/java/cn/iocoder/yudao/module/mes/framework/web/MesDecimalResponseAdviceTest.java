package cn.iocoder.yudao.module.mes.framework.web;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.MesProTaskController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MesDecimalResponseAdviceTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final MesDecimalResponseAdvice advice = new MesDecimalResponseAdvice(mapper);

    @Test
    void maximumQuantityAndMicroQuantitySurviveNestedPagesAndMaps() throws Exception {
        var page = new PageResult<>(List.of(Map.of("quantity", new BigDecimal("99999999999999.999999"),
                "remaining", List.of(new BigDecimal("0.000001")), "enabled", true)), 1L);
        var body = CommonResult.success(page);
        Object response = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, null, null);
        assertInstanceOf(CommonResult.class, response);
        JsonNode result = mapper.valueToTree(response);
        assertEquals(0, result.get("code").asInt());
        assertEquals(1, result.at("/data/total").asInt());
        assertEquals("99999999999999.999999", result.at("/data/list/0/quantity").textValue());
        assertEquals("0.000001", result.at("/data/list/0/remaining/0").textValue());
        assertTrue(result.at("/data/list/0/enabled").isBoolean());
        assertTrue(mapper.readTree(mapper.writeValueAsString(body)).at("/data/list/0/quantity").isNumber());
    }

    @Test
    void limitsAdviceToMesJsonAndLeavesDownloadsAndOtherModulesAlone() throws Exception {
        var mes = new MethodParameter(MesProTaskController.class.getMethod("getTask", Long.class), -1);
        var other = new MethodParameter(OtherController.class.getMethod("get"), -1);
        assertTrue(advice.supports(mes, MappingJackson2HttpMessageConverter.class));
        assertFalse(advice.supports(other, MappingJackson2HttpMessageConverter.class));
        assertFalse(advice.supports(mes, ByteArrayHttpMessageConverter.class));
        assertNull(advice.beforeBodyWrite(null, mes, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, null, null));
    }

    @Test
    void amountDimensionAndPercentageContractsRemainNumeric() {
        var data = Map.of("unitPrice", new BigDecimal("12.50"), "area", new BigDecimal("100.25"),
                "rate", new BigDecimal("0.125"), "changeRate", new BigDecimal("0.000001"),
                "todayOutput", new BigDecimal("99999999999999.999999"));
        JsonNode response = mapper.valueToTree(advice.beforeBodyWrite(CommonResult.success(data), null,
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class, null, null));
        assertTrue(response.at("/data/unitPrice").isNumber());
        assertTrue(response.at("/data/area").isNumber());
        assertTrue(response.at("/data/rate").isNumber());
        assertEquals("0.000001", response.at("/data/changeRate").textValue());
        assertEquals("99999999999999.999999", response.at("/data/todayOutput").textValue());
    }

    public static class OtherController { public Object get() { return null; } }
}
