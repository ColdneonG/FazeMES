package cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class MesWmReturnSalesSourceRespVO {
    private Long id,originTransactionId,salesId,salesLineId,itemId,unitMeasureId,batchId,materialStockId,warehouseId,locationId,areaId;
    private String salesCode,itemCode,itemName,itemSpecification,unitMeasureName,batchCode,warehouseName,locationName,areaName;
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") private LocalDateTime outboundAt;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal outboundQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal returnedQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal reservedQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal availableQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal editableAvailableQuantity;
    private boolean sourceReliable;
    private String sourceWarning;
}
