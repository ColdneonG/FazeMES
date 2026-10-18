package cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/** Append-only station snapshot. No update/delete API is exposed. */
@TableName("mes_pro_sn_station")
@KeySequence("mes_pro_sn_station_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProSnStationDO extends TenantBaseDO {
    @TableId private Long id;
    private Long traceId;
    private String requestKey;
    private String requestHash;
    private Long workOrderId;
    private Long feedbackId;
    private String feedbackCode;
    private Long taskId;
    private Long routeId;
    private Long processId;
    private Integer processSort;
    private Long workstationId;
    private Long operatorId;
    private Long feedbackUserId;
    private LocalDateTime stationTime;
    private LocalDateTime feedbackTime;
}
