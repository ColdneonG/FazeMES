package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

public final class MesAssistantErrors {
    private MesAssistantErrors() {}
    public static ServiceException invalid(String message) { return new ServiceException(1_047_140_000, message); }
    public static ServiceException missing() { return new ServiceException(1_047_140_001, "记录不存在或无权访问"); }
    public static ServiceException model(String message) { return new ServiceException(1_047_140_002, message); }
    public static ServiceException budget() { return new ServiceException(1_047_140_003, "模型预算尚未配置或未启用；请管理员配置调用配额，知识检索和规则查询仍可使用"); }
    public static ServiceException quota() { return new ServiceException(1_047_140_004, "模型调用已达到当前配额或存在待核定用量；请查看用量审计或联系管理员"); }
}
