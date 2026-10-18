package cn.iocoder.yudao.module.mes.service.pro.sntrace;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
public interface SnTraceErrorConstants {
    ErrorCode NOT_FOUND = new ErrorCode(1_047_120_000, "单件追溯记录或来源不存在");
    ErrorCode IDENTITY_INVALID = new ErrorCode(1_047_120_001, "SN与工单产品或生产批次不一致，或工单产品未设置");
    ErrorCode FEEDBACK_INVALID = new ErrorCode(1_047_120_002, "仅可绑定同工单、同产品、任务工序一致的已完成报工，且报工数量必须为正整数");
    ErrorCode SOURCE_INVALID = new ErrorCode(1_047_120_003, "用料必须来自该报工已完成的本工单WIP实际批次耗料");
    ErrorCode SHARE_INVALID = new ErrorCode(1_047_120_004, "用料分配不完整、超过剩余实际耗料，或不等于该物料的一件份额");
    ErrorCode STATION_INVALID = new ErrorCode(1_047_120_005, "该SN已完成此工序、前序尚未完成、路线不一致或路线顺序无效");
    ErrorCode REQUEST_CONFLICT = new ErrorCode(1_047_120_006, "请求号已用于其他登记内容，请刷新后重试");
    ErrorCode REFERENCED_SN = new ErrorCode(1_047_120_007, "SN已登记单件追溯，不允许删除");
    ErrorCode FEEDBACK_FULL = new ErrorCode(1_047_120_008, "该报工的单件登记数已达到实际产量");
    ErrorCode SN_REQUIRED = new ErrorCode(1_047_120_009, "请提供有效SN编号或已有SN ID，SN编号不能重复");
}
