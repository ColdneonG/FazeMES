package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** Immutable identity of an explicitly registered, individually tracked product. */
@TableName("mes_pro_sn_trace")
@KeySequence("mes_pro_sn_trace_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProSnTraceDO extends TenantBaseDO {
    @TableId private Long id;
    private Long snId;
    private String snCode;
    private Long workOrderId;
    private Long productId;
    private String productBatchCode;
    private String status;
}
