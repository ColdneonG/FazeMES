package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mes_sop_version")
@KeySequence("mes_sop_version_seq")
public class MesSopVersionDO extends BaseDO {
    @TableId private Long id;
    private Long documentId;
    private String versionNo;
    private String title;
    private String changeNote;
    private String status;
    private String stepsJson;
    private String attachmentsJson;
    private String modelUrl;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private Long disabledBy;
    private LocalDateTime disabledAt;
    private String disableReason;
}
