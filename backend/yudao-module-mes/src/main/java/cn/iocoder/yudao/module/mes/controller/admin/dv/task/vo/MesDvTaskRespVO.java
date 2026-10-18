package cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.*;
import lombok.*;
import java.util.List;
@Data @EqualsAndHashCode(callSuper = true)
public class MesDvTaskRespVO extends MesDvTaskDO {
    private List<MesDvTaskLineDO> lines;
}
