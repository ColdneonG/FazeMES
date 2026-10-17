package cn.iocoder.yudao.module.infra.controller.admin.monitor.vo;
import java.util.List;
/** Explicit allowlist: never includes environment, VM arguments, home path or command line. */
public record ServerMonitorRespVO(long sampleTime,long startedTime,long uptimeMillis,String javaVersion,
    String jvmName,String osName,String osArch,int processors,Double systemCpuLoad,Double processCpuLoad,
    long heapUsed,long heapCommitted,long heapMax,long nonHeapUsed,Long physicalMemoryTotal,Long physicalMemoryFree,
    int liveThreads,int peakThreads,int loadedClasses,long gcCount,long gcMillis,List<Disk> disks) {
    public record Disk(String root,long total,long usable) {}
}
