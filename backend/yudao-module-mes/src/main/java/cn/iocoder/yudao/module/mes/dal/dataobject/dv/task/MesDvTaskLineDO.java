package cn.iocoder.yudao.module.mes.dal.dataobject.dv.task;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
/** Project snapshot retained when a plan is later edited or disabled. */
@TableName("mes_dv_task_line")
@KeySequence("mes_dv_task_line_seq")
@Data @EqualsAndHashCode(callSuper = true)
public class MesDvTaskLineDO extends BaseDO {
    @TableId private Long id;
    private Long taskId;
    private Long subjectId;
    private String subjectName;
    private String content;
    private String standard;
    private Integer result;
    private String description;
}
