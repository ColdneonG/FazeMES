package cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

/** Read-only, current notice fulfillment. All quantities are exact decimal strings in JSON. */
@Data
public class MesWmSalesFulfillmentRespVO {
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal noticeQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal shippedQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal reservedQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal availableQuantity;
    @JsonFormat(shape=JsonFormat.Shape.STRING) private BigDecimal editableAvailableQuantity;
    @Schema(description="Whether historical document and posted ledger evidence agree")
    private boolean fulfillmentReliable=true;
    private String fulfillmentWarning;
}
