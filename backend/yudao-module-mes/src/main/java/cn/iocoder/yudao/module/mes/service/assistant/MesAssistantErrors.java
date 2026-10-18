package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

public final class MesAssistantErrors {
    private MesAssistantErrors() {}
    public static ServiceException invalid(String message) { return new ServiceException(1_047_140_000, message); }
    public static ServiceException missing() { return new ServiceException(1_047_140_001, "记录不存在或无权访问"); }
    public static ServiceException model(String message) { return new ServiceException(1_047_140_002, message); }
}
