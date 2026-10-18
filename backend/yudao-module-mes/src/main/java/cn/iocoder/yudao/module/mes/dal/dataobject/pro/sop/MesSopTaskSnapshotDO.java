package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mes_sop_task_snapshot")
@KeySequence("mes_sop_task_snapshot_seq")
public class MesSopTaskSnapshotDO extends BaseDO {
    @TableId private Long id;
    private Long taskId;
    private Long itemId;
    private Long processId;
    private Long documentId;
    private Long versionId;
    private String versionNo;
    private String title;
    private String stepsJson;
    private String attachmentsJson;
    private String modelUrl;
}
