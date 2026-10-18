package cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.*;
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProSnTracePageReqVO extends PageParam {
    private Long workOrderId;
    private String snCode;
    private String batchCode;
}
