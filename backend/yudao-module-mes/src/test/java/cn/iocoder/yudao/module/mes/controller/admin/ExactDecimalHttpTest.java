package cn.iocoder.yudao.module.mes.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.mes.framework.web.MesDecimalResponseAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExactDecimalHttpTest {
    @Test
    void actualHttpEnvelopeAndAuditAdviceKeepMaximumAndMicroQuantitiesExact() throws Exception {
        var mapper = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(new DecimalController())
                .setControllerAdvice(new MesDecimalResponseAdvice(mapper), new GlobalResponseBodyHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(get("/mes/test/exact-decimal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows[0].quantity").isString())
                .andExpect(jsonPath("$.data.rows[0].quantity").value("99999999999999.999999"))
                .andExpect(jsonPath("$.data.rows[0].remaining").value("0.000001"));
    }

    @RestController
    static class DecimalController {
        @GetMapping("/mes/test/exact-decimal")
        public CommonResult<?> get() {
            return CommonResult.success(Map.of("rows", List.of(Map.of(
                    "quantity", new BigDecimal("99999999999999.999999"),
                    "remaining", new BigDecimal("0.000001")))));
        }
    }
}
