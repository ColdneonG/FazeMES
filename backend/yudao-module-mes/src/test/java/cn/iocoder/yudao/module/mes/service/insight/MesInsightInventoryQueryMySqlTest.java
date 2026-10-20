package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightInventoryFixture.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native InnoDB read-view test. Requires an explicitly empty disposable mes_insight_it_* schema. */
@EnabledIfEnvironmentVariable(named="MES_INSIGHT_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:59685/mes_insight_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesInsightInventoryQueryMySqlTest {
    DriverManagerDataSource ds; JdbcTemplate jdbc; SecurityFrameworkService security; String schema; boolean ownsTables;
    Clock clock=Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"),ZoneId.of("Asia/Shanghai"));
    @BeforeEach void setup() {
        String url=System.getenv("MES_INSIGHT_MYSQL_URL"); URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost()); assertEquals(59685,uri.getPort()); schema=uri.getPath().substring(1);
        assertTrue(schema.matches("mes_insight_it_[A-Za-z0-9_]+"));
        ds=new DriverManagerDataSource(url,System.getenv("MES_INSIGHT_MYSQL_USER"),System.getenv("MES_INSIGHT_MYSQL_PASSWORD")); jdbc=new JdbcTemplate(ds);
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class)); assertEquals(59685,jdbc.queryForObject("SELECT @@port",Integer.class));
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",schema).isEmpty(),"Use an EMPTY disposable schema; application databases are forbidden");
        ownsTables=true; create(jdbc,true);
        security=mock(SecurityFrameworkService.class); when(security.hasPermission(MesInsightInventoryQuery.STOCK_PERMISSION)).thenReturn(true);
        TenantContextHolder.setTenantId(1L);
    }
    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        if(ownsTables) {
            assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class)); assertEquals(59685,jdbc.queryForObject("SELECT @@port",Integer.class));
            drop(jdbc);
        }
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("list"); }
    private void initial() {
        stock(jdbc,1,1,1,"B1","10","2026-08-01 00:00:00"); tx(jdbc,1,1,1,1,"B1","10","2026-08-01 00:00:00",110,10,"RK-1");
    }
    @Test void concurrentPostingIsWhollyOutsideAlreadyEstablishedSnapshot() throws Exception {
        initial(); var established=new CountDownLatch(1); var committed=new CountDownLatch(1); var executor=Executors.newSingleThreadExecutor();
        Future<?> writer=executor.submit(()-> {
            try {
                assertTrue(established.await(10,TimeUnit.SECONDS));
                var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
                tx.executeWithoutResult(status->{
                    jdbc.update("UPDATE mes_wm_material_stock SET quantity=quantity+10 WHERE id=1 AND tenant_id=1");
                    tx(jdbc,2,1,1,1,"B1","10","2026-09-15 00:00:00",110,11,"RK-2");
                });
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            finally { committed.countDown(); }
        });
        try {
            var query=new MesInsightInventoryQuery(jdbc,security,new DataSourceTransactionManager(ds),clock) {
                @Override protected void snapshotEstablished() {
                    established.countDown();
                    try { assertTrue(committed.await(10,TimeUnit.SECONDS)); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }
            };
            var result=query.inventory(map("year",2026,"month",9)); writer.get(10,TimeUnit.SECONDS);
            assertEquals("10",rows(result).get(0).get("openingQuantity"));
            assertEquals("0",rows(result).get(0).get("inboundQuantity"));
            assertEquals("10",rows(result).get(0).get("closingQuantity")); assertEquals(true,result.get("complete"));
            assertEquals("20.000000",jdbc.queryForObject("SELECT quantity FROM mes_wm_material_stock WHERE id=1",java.math.BigDecimal.class).toPlainString());
            // A new business question must use the later committed snapshot, never reuse the old answer.
            var fresh=new MesInsightInventoryQuery(jdbc,security,new DataSourceTransactionManager(ds),clock).inventory(map("year",2026,"month",9));
            assertEquals("10",rows(fresh).get(0).get("inboundQuantity")); assertEquals("20",rows(fresh).get(0).get("closingQuantity"));
        } finally { established.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void nativeDecimalBoundaryAndTransferSignedTotals() {
        stock(jdbc,1,1,1,"B1","1.000001","2024-01-01 00:00:00");
        tx(jdbc,1,1,1,1,"B1","1","2024-01-01 00:00:00",110,10,"A");
        tx(jdbc,2,1,1,1,"B1","0.000002","2024-02-29 23:59:59",110,20,"B");
        tx(jdbc,3,1,1,1,"B1","-0.000001","2024-03-01 00:00:00",118,30,"C");
        var query=new MesInsightInventoryQuery(jdbc,security,new DataSourceTransactionManager(ds),clock);
        var row=rows(query.inventory(map("year",2024,"month",2))).get(0);
        assertEquals("1",row.get("openingQuantity")); assertEquals("0.000002",row.get("inboundQuantity")); assertEquals("1.000002",row.get("closingQuantity"));
        tx(jdbc,4,1,1,1,"B1","-1","2026-09-01 00:00:00",111,40,"TRANSFER");
        tx(jdbc,5,1,1,1,"B1","1","2026-09-01 00:00:00",112,40,"TRANSFER");
        var transfer=query.document(map("documentCode","TRANSFER")); assertEquals(2,rows(transfer).size());
        assertEquals(111,((Map<?,?>)transfer.get("document")).get("bizType"));
        assertEquals("0",((Map<?,?>)((List<?>)transfer.get("unitGroups")).get(0)).get("netQuantity"));
    }
}
