package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;

/** A share of a finished feedback's actual WIP consumption, never a stock movement. */
@TableName("mes_pro_sn_material")
@KeySequence("mes_pro_sn_material_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProSnMaterialDO extends TenantBaseDO {
    @TableId private Long id;
    private Long traceId;
    private Long stationId;
    private Long feedbackId;
    private Long consumeId;
    private Long consumeLineId;
    private Long consumeDetailId;
    private Long materialStockId;
    private Long itemId;
    private Long batchId;
    private String batchCode;
    private BigDecimal quantity;
}
