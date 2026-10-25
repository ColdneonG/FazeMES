package cn.iocoder.yudao.module.mes.framework.web;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Preserve MES decimal values before a browser's JSON parser can round them. */
@RestControllerAdvice(basePackages = "cn.iocoder.yudao.module.mes.controller")
public class MesDecimalResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper decimalMapper;
    private static final Set<String> QUANTITY_NAMES = Set.of("minstock", "maxstock", "changerate",
            "todayoutput", "yesterdayoutput", "planned", "produced", "qualified", "rejected", "target",
            "remaining", "available", "reserved", "required", "opening", "closing", "received", "issued", "returned");

    public MesDecimalResponseAdvice(ObjectMapper objectMapper) {
        SimpleModule module = new SimpleModule("MesExactDecimals");
        module.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                var context = generator.getOutputContext();
                while (context != null && !context.inObject()) context = context.getParent();
                String name = context == null ? "" : context.getCurrentName();
                name = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (name.contains("quantit") || name.endsWith("qty") || QUANTITY_NAMES.contains(name)) {
                    generator.writeString(value.toPlainString());
                } else {
                    // Amounts, dimensions, rates and other existing numeric
                    // controls retain their own independently defined contracts.
                    generator.writeNumber(value);
                }
            }
        });
        // Keep the application's dates, field annotations and identifier formats.
        // The shared mapper and non-MES HTTP contracts are not changed.
        decimalMapper = objectMapper.copy().registerModule(module);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getContainingClass().getPackageName().startsWith("cn.iocoder.yudao.module.mes.controller.")
                && AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof CommonResult<?> result) {
            // The audit ResponseBodyAdvice also handles CommonResult. Retain
            // that envelope regardless of the two advices' execution order.
            return new CommonResult<>().setCode(result.getCode()).setMsg(result.getMsg())
                    .setData(result.getData() == null ? null : decimalMapper.valueToTree(result.getData()));
        }
        return body == null ? null : decimalMapper.valueToTree(body);
    }
}
