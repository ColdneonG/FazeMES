package cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;

@TableName("mes_pro_shortage_followup")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProShortageFollowupDO extends BaseDO {
    @TableId private Long id;
    private Long workOrderId;
    private Long itemId;
    private LocalDate expectedDate;
    private String trackingStatus;
    private String remark;
}
