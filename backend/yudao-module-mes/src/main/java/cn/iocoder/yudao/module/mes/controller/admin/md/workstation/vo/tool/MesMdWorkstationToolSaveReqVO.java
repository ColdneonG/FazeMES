package cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Schema(description = "管理后台 - MES 工装夹具资源新增/修改 Request VO")
@Data
public class MesMdWorkstationToolSaveReqVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "工作站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工作站不能为空")
    private Long workstationId;

    @Schema(description = "工具类型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工具类型不能为空")
    private Long toolTypeId;

    @Schema(description = "数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于零")
    private Integer quantity;

    @Schema(description = "绑定实际工装台账编号；空列表表示未限制")
    @Size(max = 100, message = "最多绑定100项资源")
    private List<@NotNull @Positive Long> toolIds;

    @Schema(description = "备注")
    private String remark;

}
