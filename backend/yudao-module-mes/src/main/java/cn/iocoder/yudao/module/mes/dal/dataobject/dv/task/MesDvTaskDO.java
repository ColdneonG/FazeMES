package cn.iocoder.yudao.module.mes.dal.dataobject.dv.task;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;
/** One immutable plan/equipment/due-time occurrence. Status: 0 pending, 1 claimed, 2 completed. */
@TableName("mes_dv_task")
@KeySequence("mes_dv_task_seq")
@Data @EqualsAndHashCode(callSuper = true)
public class MesDvTaskDO extends BaseDO {
    @TableId private Long id;
    private Long planId;
    private String planName;
    private Long machineryId;
    private String machineryName;
    private Integer type;
    private LocalDateTime dueTime;
    private Integer status;
    private Long userId;
    private LocalDateTime claimedTime;
    private LocalDateTime completedTime;
    private Long recordId;
    private Long repairId;
}
