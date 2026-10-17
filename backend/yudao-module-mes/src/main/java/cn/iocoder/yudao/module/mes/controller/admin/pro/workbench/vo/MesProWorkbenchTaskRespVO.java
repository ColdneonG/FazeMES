package cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工位端所需的最小任务视图。 */
@Data
public class MesProWorkbenchTaskRespVO {
    private Long id;
    private String code;
    private String name;
    private Long workstationId;
    private String workstationName;
    private Long workOrderId;
    private String workOrderCode;
    private String workOrderName;
    private Long routeId;
    private Long processId;
    private String processName;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private String itemSpecification;
    private BigDecimal quantity;
    private BigDecimal producedQuantity;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean checkFlag;
}
