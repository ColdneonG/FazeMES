package cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** A reservation belongs to one order and one exact physical stock record. */
@TableName("mes_pro_material_reservation")
@Data
@EqualsAndHashCode(callSuper = true)
public class MesProMaterialReservationDO extends BaseDO {
    @TableId private Long id;
    private Long workOrderId;
    private Long itemId;
    private Long materialStockId;
    private BigDecimal quantity;
    private BigDecimal allocatedQuantity;
    private BigDecimal consumedQuantity;
    private BigDecimal releasedQuantity;
}
