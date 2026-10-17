package cn.iocoder.yudao.module.mes.controller.admin.qc.analysis.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MesQcComparisonReqVO {
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    /** YEAR_AGO去年同期；PREVIOUS上一等长周期。 */
    private String compareMode = "YEAR_AGO";
    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal rateThreshold = new BigDecimal("5");
    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal increaseThreshold = new BigDecimal("2");
}
