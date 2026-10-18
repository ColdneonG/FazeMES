package cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - MES 产品BOM新增/修改 Request VO")
@Data
public class MesMdProductBomSaveReqVO {

    @Schema(description = "BOM编号", example = "1024")
    private Long id;

    @Schema(description = "物料产品ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "物料产品ID不能为空")
    private Long itemId;

    @Schema(description = "BOM物料ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "BOM物料ID不能为空")
    private Long bomItemId;

    @Schema(description = "物料使用比例", requiredMode = Schema.RequiredMode.REQUIRED, example = "1.5")
    @NotNull(message = "物料使用比例不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "物料使用比例必须大于零")
    @Digits(integer = 14, fraction = 6, message = "物料使用比例最多十四位整数及六位小数")
    private BigDecimal quantity;

    @Schema(description = "是否启用", example = "0")
    private Integer status;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
