package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mes_sop_document")
@KeySequence("mes_sop_document_seq")
public class MesSopDocumentDO extends BaseDO {
    @TableId private Long id;
    private String code;
    private String name;
    private Long itemId;
    private Long processId;
}
