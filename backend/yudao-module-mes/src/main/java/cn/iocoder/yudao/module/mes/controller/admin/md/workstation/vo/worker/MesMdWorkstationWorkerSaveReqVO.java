package cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.worker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Schema(description = "管理后台 - MES 人力资源新增/修改 Request VO")
@Data
public class MesMdWorkstationWorkerSaveReqVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "工作站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工作站不能为空")
    private Long workstationId;

    @Schema(description = "岗位编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "岗位不能为空")
    private Long postId;

    @Schema(description = "数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于零")
    private Integer quantity;

    @Schema(description = "绑定实际人员编号；空列表表示未限制")
    @Size(max = 100, message = "最多绑定100项资源")
    private List<@NotNull @Positive Long> userIds;

    @Schema(description = "备注")
    private String remark;

}
