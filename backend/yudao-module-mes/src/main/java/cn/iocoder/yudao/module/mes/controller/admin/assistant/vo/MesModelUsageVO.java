package cn.iocoder.yudao.module.mes.controller.admin.assistant.vo;

import java.util.List;

/** Integer and monetary values deliberately remain decimal strings at the HTTP boundary. */
public final class MesModelUsageVO {
    private MesModelUsageVO() { }
    public record Limits(String dayCalls,String monthCalls,String dayTokens,String monthTokens,
                         String dayAmount,String monthAmount,int maxConcurrency,int callsPerMinute) { }
    public record PriceCard(String channel,String provider,String model,String inputPerMillion,
                            String outputPerMillion,String perCallReservationAmount) { }
    public record PolicySaveRequest(int expectedVersion,boolean enabled,Limits tenantLimits,
                                    Limits defaultUserLimits,List<PriceCard> rates,String reason) { }
    public record ReconcileRequest(String usageId,int expectedVersion,String action,String inputTokens,
                                    String outputTokens,String confirmedAmount,String reason) { }
    public record UsagePageRequest(String from,String to,String purpose,String status,Long userId,
                                    Integer pageNo,Integer pageSize) { }
}
