package cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.math.BigDecimal;

/** Minimal source context for a QC operator; no warehouse query permission or cost data. */
@Data
public class MesQcRqcSourceContextRespVO {
    private Integer sourceDocType;
    private Long sourceDocId;
    private String sourceDocCode;
    private Long sourceLineId;
    private Long clientId;
    private Long itemId;
    private Long batchId;
    private String batchCode;
    @JsonSerialize(using=ToStringSerializer.class)
    private BigDecimal checkQuantity;
    private Long originTransactionId;
}
