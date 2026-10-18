package cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
@Data
public class MesProSnTraceRespVO {
    private MesProSnTraceDO sn;
    private List<Station> stations;
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Station extends MesProSnStationDO {
        private List<MesProSnMaterialDO> materials;
    }
    @Data
    public static class ConsumeOption {
        private Long consumeDetailId;
        private Long consumeLineId;
        private Long itemId;
        private Long batchId;
        private String batchCode;
        private Long materialStockId;
        private BigDecimal quantity;
        private BigDecimal allocatedQuantity;
        private BigDecimal availableQuantity;
        private BigDecimal unitQuantity;
    }
}
