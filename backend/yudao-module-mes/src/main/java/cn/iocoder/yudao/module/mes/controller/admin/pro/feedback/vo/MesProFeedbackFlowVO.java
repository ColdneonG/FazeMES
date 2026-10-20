package cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Decimal quantities remain strings at the browser boundary. Source identity is a report, not a pooled lot. */
public class MesProFeedbackFlowVO {
    @Data public static class Source {
        @NotNull private Long sourceFeedbackId;
        @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=14, fraction=6)
        @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal quantity;
    }
    @Data public static class Allocation extends Source {
        private String sourceFeedbackCode;
        private boolean complete;
        private boolean legacy;
    }
    @Data public static class Option {
        private Long id;
        private String code;
        private String processName;
        @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal qualified;
        /** Frozen allocations by OTHER reports; this report's own allocation is excluded. */
        @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal used;
        @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal available;
        private boolean complete;
        private boolean legacy;
    }
    @Data public static class Context {
        private boolean firstProcess;
        private boolean required;
        private boolean complete;
        private boolean legacy;
        private List<Allocation> allocations = new ArrayList<>();
        private List<Option> options = new ArrayList<>();
    }
    @Data public static class Coverage {
        private boolean complete;
        private List<Long> legacyFeedbackIds = new ArrayList<>();
        private boolean truncated;
        private String scope = "BATCH_POOL";
        private List<String> notes = new ArrayList<>();
    }
}
