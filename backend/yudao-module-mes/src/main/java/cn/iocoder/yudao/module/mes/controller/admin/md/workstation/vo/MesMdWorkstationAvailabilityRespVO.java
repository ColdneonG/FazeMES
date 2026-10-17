package cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "工作站实时资源可用性")
public class MesMdWorkstationAvailabilityRespVO {
    private Long workstationId;
    private LocalDateTime checkedAt;
    private Boolean available;
    @Schema(description = "阻止生产的资源原因")
    private List<String> reasons = new ArrayList<>();
    @Schema(description = "未限制资源及检查说明")
    private List<String> notes = new ArrayList<>();
}
