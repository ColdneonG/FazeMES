package cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.math.BigDecimal;

/** Minimum source fields available to an OQC creator; no stock valuation or sales commercial data. */
@Data
public class MesQcOqcSourceContextRespVO {
    private Integer sourceDocType;
    private Long sourceDocId;
    private Long sourceLineId;
    private String sourceDocCode;
    private Long clientId;
    private Long itemId;
    private String batchCode;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal outQuantity;
}
