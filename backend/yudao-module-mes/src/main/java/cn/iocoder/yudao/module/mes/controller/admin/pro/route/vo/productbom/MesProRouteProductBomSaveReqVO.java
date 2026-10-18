package cn.iocoder.yudao.module.mes.controller.admin.pro.route.vo.productbom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - MES 工艺路线产品 BOM 新增/修改 Request VO")
@Data
public class MesProRouteProductBomSaveReqVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "工艺路线编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工艺路线编号不能为空")
    private Long routeId;

    @Schema(description = "工序编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工序编号不能为空")
    private Long processId;

    @Schema(description = "产品物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "产品物料编号不能为空")
    private Long productId;

    @Schema(description = "BOM 物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "BOM 物料编号不能为空")
    private Long itemId;

    @Schema(description = "用料比例", example = "1.50")
    @NotNull(message = "用料比例不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "用料比例必须大于零")
    @Digits(integer = 14, fraction = 6)
    private BigDecimal quantity;

    @Schema(description = "备注")
    private String remark;

}
