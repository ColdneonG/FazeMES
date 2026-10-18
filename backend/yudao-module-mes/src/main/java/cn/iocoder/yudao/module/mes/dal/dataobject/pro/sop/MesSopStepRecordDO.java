package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mes_sop_step_record")
@KeySequence("mes_sop_step_record_seq")
public class MesSopStepRecordDO extends BaseDO {
    @TableId private Long id;
    private Long snapshotId;
    private String stepId;
    private LocalDateTime viewedAt;
    private Long viewedBy;
    private LocalDateTime confirmedAt;
    private Long confirmedBy;
    private String parametersJson;
    private String photosJson;
}
