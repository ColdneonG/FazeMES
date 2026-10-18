package cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class KittingAnalysisVO {
    private Long workOrderId;
    private String workOrderCode;
    private String workOrderName;
    private Integer workOrderStatus;
    private String status;
    private boolean ready;
    private int shortageItemCount;
    private LocalDateTime analyzedAt;
    private List<KittingMaterialVO> materials;
}
