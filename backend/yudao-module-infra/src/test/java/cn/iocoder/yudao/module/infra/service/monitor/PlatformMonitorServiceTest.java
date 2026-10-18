package cn.iocoder.yudao.module.infra.service.monitor;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.filter.stat.StatFilter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PlatformMonitorServiceTest {
    PlatformMonitorService service=new PlatformMonitorService();
    @Test void databaseSummaryIsAllowlistedAndNeverIncludesSqlOrCredentials() {
        DruidDataSource source=mock(DruidDataSource.class);JdbcSqlStat stat=mock(JdbcSqlStat.class);
        String sensitiveSql="SELECT * FROM customer WHERE api_secret = 'fixture-sensitive'";
        when(source.getSqlStatMap()).thenReturn(Map.of(sensitiveSql,stat));when(stat.getSql()).thenReturn(sensitiveSql);
        when(stat.getExecuteCount()).thenReturn(4L);when(stat.getErrorCount()).thenReturn(1L);
        when(source.getProxyFilters()).thenReturn(List.of(new StatFilter()));
        var pool=service.readPool(source);
        assertEquals(4,pool.sqlExecuteCount());assertEquals(1,pool.sqlErrorCount());assertTrue(pool.sqlStatisticsEnabled());
        assertEquals("SELECT",pool.sql().get(0).operation());assertEquals(16,pool.sql().get(0).digest().length());
        String json=JsonUtils.toJsonString(pool);
        assertFalse(json.contains("fixture-sensitive"));assertFalse(json.contains("api_secret"));
        assertFalse(json.toLowerCase().contains("password"));assertFalse(json.toLowerCase().contains("jdbc:"));
        verify(source,never()).getUrl();verify(source,never()).getUsername();verify(source,never()).getPassword();
    }
    @Test void serverSnapshotUsesRealJvmCountersWithoutEnvironmentOrArguments() {
        var snapshot=service.server();assertTrue(snapshot.uptimeMillis()>0);assertTrue(snapshot.heapUsed()>0);
        assertTrue(snapshot.processors()>0);assertTrue(snapshot.liveThreads()>0);
        String json=JsonUtils.toJsonString(snapshot);
        assertFalse(json.contains("java.class.path"));assertFalse(json.contains("user.home"));assertFalse(json.contains("inputArguments"));
    }
}
