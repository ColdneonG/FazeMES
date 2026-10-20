package cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MesWmQualityHoldRespVO {
    private Long id;
    private Long materialStockId;
    private Long sourceTransactionId;
    private Integer sourceBizType;
    private Long sourceBizId;
    private Long sourceBizLineId;
    private Long arrivalTransactionId;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal observedQuantity;
    private String reason;
    private String status;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime createTime;
}
