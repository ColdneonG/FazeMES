package cn.iocoder.yudao.module.mes.controller.admin.qc.analysis.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class MesQcComparisonRespVO {
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate comparisonStartDate;
    private LocalDate comparisonEndDate;
    private List<Row> rows;

    @Data
    public static class Row {
        private String stage;
        private long currentTotal;
        private long currentFailed;
        private BigDecimal currentRate;
        private long comparisonTotal;
        private long comparisonFailed;
        private BigDecimal comparisonRate;
        private BigDecimal changePoints;
        private boolean alert;
        private String message;
    }
}
