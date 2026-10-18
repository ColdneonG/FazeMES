package cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@TableName("mes_pro_reservation_event")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProReservationEventDO extends BaseDO {
    @TableId private Long id;
    private Long workOrderId;
    private Long itemId;
    private Long materialStockId;
    private Long reservationId;
    private String action;
    private BigDecimal quantity;
    private Long referenceId;
    private Long operatorId;
}
