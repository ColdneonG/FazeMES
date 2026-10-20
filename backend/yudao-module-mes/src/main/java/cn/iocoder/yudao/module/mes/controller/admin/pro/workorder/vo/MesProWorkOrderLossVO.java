package cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Explicit operational loss closure; planned and reported quantities are never rewritten. */
public class MesProWorkOrderLossVO {
    @Data
    public static class CloseRequest {
        @NotNull private Long id;
        @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
        private BigDecimal expectedProducedQuantity;
        @NotBlank @Size(max = 500) private String reason;
    }
    @Data
    public static class Record {
        private Long workOrderId;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal plannedQuantity;
        /** Final-process completed quantity, including final-process rejects. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) private BigDecimal producedQuantity;
        @JsonFormat(shape = JsonFormat.Shape.STRING) private BigDecimal qualifiedQuantity;
        /** Loss before the final process; final rejects remain separate quality records. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) private BigDecimal lossQuantity;
        private String reason;
        private Long closedBy;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") private LocalDateTime closedAt;
    }
}
