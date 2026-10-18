package cn.iocoder.yudao.module.infra.service.monitor;
import cn.iocoder.yudao.module.infra.controller.admin.monitor.vo.*;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.stat.*;
import com.alibaba.druid.filter.stat.StatFilter;
import org.springframework.stereotype.Service;
import java.io.File;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Service
public class PlatformMonitorService {
    public ServerMonitorRespVO server() {
        RuntimeMXBean runtime=ManagementFactory.getRuntimeMXBean();
        var memory=ManagementFactory.getMemoryMXBean();var threads=ManagementFactory.getThreadMXBean();
        var os=ManagementFactory.getOperatingSystemMXBean();
        Double systemCpu=null,processCpu=null;Long total=null,free=null;
        if(os instanceof com.sun.management.OperatingSystemMXBean extended) {
            systemCpu=load(extended.getCpuLoad());processCpu=load(extended.getProcessCpuLoad());
            total=extended.getTotalMemorySize();free=extended.getFreeMemorySize();
        }
        long gcCount=0,gcMillis=0;
        for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount+=Math.max(0,gc.getCollectionCount());gcMillis+=Math.max(0,gc.getCollectionTime());
        }
        List<ServerMonitorRespVO.Disk> disks=new ArrayList<>();
        for(File root:File.listRoots()) if(root.getTotalSpace()>0)
            disks.add(new ServerMonitorRespVO.Disk(root.getAbsolutePath(),root.getTotalSpace(),root.getUsableSpace()));
        var heap=memory.getHeapMemoryUsage();
        return new ServerMonitorRespVO(System.currentTimeMillis(),runtime.getStartTime(),runtime.getUptime(),
            System.getProperty("java.version"),System.getProperty("java.vm.name"),os.getName(),os.getArch(),os.getAvailableProcessors(),
            systemCpu,processCpu,heap.getUsed(),heap.getCommitted(),heap.getMax(),memory.getNonHeapMemoryUsage().getUsed(),
            total,free,threads.getThreadCount(),threads.getPeakThreadCount(),ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
            gcCount,gcMillis,disks);
    }
    private Double load(double value) {return value<0?null:Math.min(1,value);}
    public DatabaseMonitorRespVO database() {
        List<DatabaseMonitorRespVO.Pool> pools=new ArrayList<>();
        for(var candidate:DruidDataSourceStatManager.getDruidDataSourceInstances())
            if(candidate instanceof DruidDataSource source) pools.add(readPool(source));
        pools.sort(Comparator.comparingLong(DatabaseMonitorRespVO.Pool::id));
        return new DatabaseMonitorRespVO(System.currentTimeMillis(),pools);
    }
    DatabaseMonitorRespVO.Pool readPool(DruidDataSource source) {
        List<DatabaseMonitorRespVO.Sql> sql=new ArrayList<>();
        Map<String,JdbcSqlStat> stats=source.getSqlStatMap();
        if(stats!=null) for(var stat:new ArrayList<>(stats.values())) {
            String text=stat.getSql();
            sql.add(new DatabaseMonitorRespVO.Sql(stat.getId(),digest(text),operation(text),stat.getExecuteCount(),stat.getErrorCount(),
                stat.getExecuteMillisTotal(),stat.getExecuteMillisMax(),stat.getFetchRowCount(),stat.getUpdateCount()));
        }
        long execute=sql.stream().mapToLong(DatabaseMonitorRespVO.Sql::executeCount).sum();
        long errors=sql.stream().mapToLong(DatabaseMonitorRespVO.Sql::errorCount).sum();
        sql.sort(Comparator.comparingLong(DatabaseMonitorRespVO.Sql::totalMillis).reversed());
        boolean statEnabled=source.getProxyFilters().stream().anyMatch(filter->filter instanceof StatFilter);
        return new DatabaseMonitorRespVO.Pool(source.getID(),source.getDbType(),source.isEnable(),source.isClosed(),statEnabled,
            source.getActiveCount(),source.getPoolingCount(),source.getMaxActive(),source.getMinIdle(),source.getActivePeak(),
            source.getWaitThreadCount(),source.getNotEmptyWaitCount(),source.getConnectCount(),source.getCloseCount(),
            source.getCreateCount(),source.getDestroyCount(),source.getErrorCount(),execute,errors,sql.stream().limit(50).toList());
    }
    private String digest(String sql) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            Objects.toString(sql,"").getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private String operation(String sql) {
        String trimmed=Objects.toString(sql,"").stripLeading().toUpperCase(Locale.ROOT);
        for(String op:List.of("SELECT","INSERT","UPDATE","DELETE","WITH","ALTER","CREATE","DROP","SHOW","SET"))
            if(trimmed.startsWith(op+" ") || trimmed.startsWith(op+"\n")) return op;
        return "OTHER";
    }
}
