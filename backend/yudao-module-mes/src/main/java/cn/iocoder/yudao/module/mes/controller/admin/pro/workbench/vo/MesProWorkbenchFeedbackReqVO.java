package cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class MesProWorkbenchFeedbackReqVO {
    @NotNull(message = "请选择生产任务")
    private Long taskId;
    @NotNull(message = "请选择审核人")
    private Long approveUserId;
    @DecimalMin(value = "0", inclusive = false, message = "报工数量必须大于0")
    private BigDecimal feedbackQuantity;
    @DecimalMin(value = "0", message = "合格品数量不能为负数")
    private BigDecimal qualifiedQuantity;
    @DecimalMin(value = "0", message = "不良品数量不能为负数")
    private BigDecimal unqualifiedQuantity;
    private String remark;
    @jakarta.validation.Valid
    @jakarta.validation.constraints.Size(max=1000)
    private java.util.List<cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackFlowVO.Source> sources;
}
