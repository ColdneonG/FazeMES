package cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDate;

@Data
public class KittingFollowupReqVO {
    @NotNull private Long workOrderId;
    @NotNull private Long itemId;
    @NotNull private String trackingStatus;
    private LocalDate expectedDate;
    @Size(max = 500) private String remark;
}
