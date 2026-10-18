package cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class KittingMaterialVO {
    private Long itemId;
    private String itemCode;
    private String itemName;
    private String unitName;
    private BigDecimal requiredQuantity;
    private BigDecimal issuedQuantity;
    private BigDecimal outstandingQuantity;
    private BigDecimal physicalQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal usableReservedQuantity;
    private BigDecimal otherReservedQuantity;
    private BigDecimal freeQuantity;
    private BigDecimal availableQuantity;
    private BigDecimal shortageQuantity;
    private String trackingStatus;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expectedDate;
    private String remark;
}
