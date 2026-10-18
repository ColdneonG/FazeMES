package cn.iocoder.yudao.module.mes.service.pro.sop;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

public interface SopErrorConstants {
    ErrorCode NOT_FOUND = new ErrorCode(1_047_110_000, "受控SOP记录不存在");
    ErrorCode INVALID = new ErrorCode(1_047_110_001, "SOP数据无效：{}");
    ErrorCode STATE = new ErrorCode(1_047_110_002, "SOP状态不允许此操作：{}");
    ErrorCode NO_RELEASE = new ErrorCode(1_047_110_003, "该产品工序已配置受控SOP，但无有效发布版本");
    ErrorCode DISABLED = new ErrorCode(1_047_110_004, "任务绑定的SOP版本已停用，请联系工艺负责人");
    ErrorCode INCOMPLETE = new ErrorCode(1_047_110_005, "请先完成任务SOP必做步骤：{}");
    ErrorCode VIEW_TIME = new ErrorCode(1_047_110_006, "请先查看步骤并达到最短查看时长（{}秒）");
    ErrorCode DUPLICATE = new ErrorCode(1_047_110_007, "产品工序或文档版本号重复");
}
