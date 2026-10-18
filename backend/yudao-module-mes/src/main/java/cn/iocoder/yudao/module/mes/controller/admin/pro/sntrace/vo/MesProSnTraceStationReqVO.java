package cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
@Data
public class MesProSnTraceStationReqVO {
    @NotBlank @Size(max = 64) private String requestKey;
    @NotNull @Positive private Long traceId;
    @NotNull @Positive private Long feedbackId;
    @Valid @NotNull @Size(max = 500) private List<Material> materials = new ArrayList<>();
    @Data
    public static class Material {
        @NotNull @Positive private Long consumeDetailId;
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6)
        private BigDecimal quantity;
    }
}
