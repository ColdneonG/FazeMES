package cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.*;
@Data @EqualsAndHashCode(callSuper = true)
public class MesDvTaskPageReqVO extends PageParam {
    private Long planId;
    private Long machineryId;
    private Integer status;
    private Long userId;
}
