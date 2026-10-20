package cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true)
public class MesWmReturnSalesSourcePageReqVO extends PageParam {
    @NotNull private Long returnId;
    private Long lineId;
    private Long originTransactionId;
    private String salesCode,itemCode,batchCode;
}
