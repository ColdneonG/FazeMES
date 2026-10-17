package cn.iocoder.yudao.module.infra.controller.admin.monitor.vo;
import java.util.List;
/** SQL is summarized by digest and operation only; SQL text, bind values, URL and credentials are excluded. */
public record DatabaseMonitorRespVO(long sampleTime,List<Pool> pools) {
    public record Pool(long id,String databaseType,boolean enabled,boolean closed,boolean sqlStatisticsEnabled,
        int active,int idle,int maxActive,int minIdle,int peakActive,int waitingThreads,long waitCount,
        long borrowed,long returned,long created,long destroyed,long errors,long sqlExecuteCount,long sqlErrorCount,
        List<Sql> sql) {}
    public record Sql(long id,String digest,String operation,long executeCount,long errorCount,
        long totalMillis,long maxMillis,long fetchedRows,long updatedRows) {}
}
