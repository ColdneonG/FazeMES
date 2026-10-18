package cn.iocoder.yudao.module.mes.enums.pro;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

public interface MesProKittingErrors {
    ErrorCode ORDER_NOT_FOUND = new ErrorCode(1_047_100_000, "生产工单不存在");
    ErrorCode ORDER_NOT_OPEN = new ErrorCode(1_047_100_001, "仅已确认工单可以预占、释放及维护缺料跟踪");
    ErrorCode NO_BOM = new ErrorCode(1_047_100_002, "工单没有有效的物料BOM，不能执行齐套预占");
    ErrorCode STOCK_RESERVED = new ErrorCode(1_047_100_003, "库存已被其他工单预占，可用于本次出库数量不足");
    ErrorCode INVALID_MATERIAL = new ErrorCode(1_047_100_004, "物料不属于当前工单BOM");
    ErrorCode INVALID_TRACKING = new ErrorCode(1_047_100_005, "缺料跟踪状态无效");
    ErrorCode INVALID_RESERVATION = new ErrorCode(1_047_100_006, "已有预占库存被冻结或不可用，请先释放后重新预占");
    ErrorCode BOM_LOCKED = new ErrorCode(1_047_100_007, "工单确认后不能修改物料BOM，请通过新工单调整生产需求");
}
