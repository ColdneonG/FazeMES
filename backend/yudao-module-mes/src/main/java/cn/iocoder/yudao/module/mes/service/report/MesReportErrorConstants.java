package cn.iocoder.yudao.module.mes.service.report;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
public final class MesReportErrorConstants {
    private MesReportErrorConstants() {}
    public static ServiceException invalid(String message) {return new ServiceException(1_047_130_000, message);}
    public static ServiceException missing() {return new ServiceException(1_047_130_001, "报表或版本不存在，或不属于当前租户");}
    public static ServiceException conflict() {return new ServiceException(1_047_130_002, "报表已被其他人修改，请重新加载后保存");}
}
