package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;

/** Physical segregation is an inventory dimension; freezing a mixed batch is not a substitute. */
public final class MesWmQualityPolicy {
    public static final String QUARANTINE_WAREHOUSE = "QUALITY_QUARANTINE_WAREHOUSE";
    public static final String QUARANTINE_LOCATION = "QUALITY_QUARANTINE_LOCATION";
    public static final String QUARANTINE_AREA = "QUALITY_QUARANTINE_AREA";

    private MesWmQualityPolicy() { }

    public static boolean isQuarantine(String code) { return QUARANTINE_WAREHOUSE.equalsIgnoreCase(code); }

    public static void validateNotSystemCode(String code) {
        if (QUARANTINE_WAREHOUSE.equalsIgnoreCase(code) || QUARANTINE_LOCATION.equalsIgnoreCase(code)
                || QUARANTINE_AREA.equalsIgnoreCase(code)) {
            throw invalid("不良品隔离仓、库区及库位为系统保留配置，不允许新增、改名、移动或删除");
        }
    }

    public static void validateInbound(Integer quality, MesWmWarehouseDO warehouse) {
        if (warehouse == null || MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE.equals(warehouse.getCode())) {
            throw invalid("退货、退料必须上架到实体仓库");
        }
        if (!MesWmQualityStatusEnum.PASS.getStatus().equals(quality)
                && !MesWmQualityStatusEnum.FAIL.getStatus().equals(quality)) {
            throw invalid("待检或质量状态不明的物料不得入库，请先完成检验");
        }
        if (MesWmQualityStatusEnum.FAIL.getStatus().equals(quality) && !isQuarantine(warehouse.getCode())) {
            throw invalid("不合格物料必须上架到不良品隔离仓，不得混入正常库存");
        }
    }

    public static void validateOutbound(MesWmWarehouseDO warehouse, MesWmMaterialStockDO stock) {
        if (isQuarantine(warehouse.getCode())) {
            throw invalid("不良品隔离仓禁止销售、领料、调拨及其他出库；须先完成受控质量处置");
        }
        validateNoHistoricalHold(stock);
    }

    public static void validateNoHistoricalHold(MesWmMaterialStockDO stock) {
        if (Boolean.TRUE.equals(stock.getQualityHold())) {
            throw invalid("历史不良库存待实物核对，禁止出库及普通解冻：" + stock.getQualityHoldReason());
        }
    }

    public static void validateInspectionSplit(java.math.BigDecimal quantity, java.math.BigDecimal qualified,
            java.math.BigDecimal rejected, Integer checkResult) {
        if (quantity == null || quantity.signum() <= 0 || qualified == null || rejected == null
                || qualified.signum() < 0 || rejected.signum() < 0
                || qualified.add(rejected).compareTo(quantity) != 0) {
            throw invalid("退货检验的合格数与不合格数必须非负，合计必须等于来源行数量");
        }
        boolean pass = cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum.PASS.getType().equals(checkResult);
        boolean fail = cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum.FAIL.getType().equals(checkResult);
        if ((!pass && !fail) || (fail && rejected.signum() == 0) || (pass && qualified.signum() == 0)) {
            throw invalid("退货检验结论与合格、不合格数量不一致，请核对实际检验结果");
        }
    }

    private static ServiceException invalid(String message) { return new ServiceException(1_047_180_001, message); }
}
