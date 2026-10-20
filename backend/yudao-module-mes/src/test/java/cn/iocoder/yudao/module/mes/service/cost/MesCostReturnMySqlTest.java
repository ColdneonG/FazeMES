package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.module.mes.service.cost.MesCostReturnServiceTest.equal;

/** InnoDB current reads and lock-order checks in an explicitly empty isolated schema only. */
@EnabledIfEnvironmentVariable(named="MES_RETURN_COST_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:59685/return_cost_java_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesCostReturnMySqlTest {
    private MesCostReturnServiceTest fixture;
    private DataSource dataSource;
    private String schema;
    private static final Set<String> TABLES=Set.of("mes_cost_profile","mes_cost_workorder","mes_cost_effort","mes_cost_line_price","mes_cost_stock_layer","mes_cost_allocation","mes_cost_return_source_balance","mes_cost_return_allocation","mes_cost_layer_basis","mes_md_item","mes_wm_product_sales","mes_wm_product_sales_line","mes_wm_return_sales","mes_wm_return_sales_line","mes_wm_transaction");
    private JdbcTemplate checked() {
        assertTrue(schema.matches("return_cost_java_it_[A-Za-z0-9_]+"));
        var jdbc=new JdbcTemplate(dataSource);assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));return jdbc;
    }
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("MES_RETURN_COST_MYSQL_URL");schema=URI.create(url.substring(5)).getPath().substring(1);
        dataSource=new DriverManagerDataSource(url,System.getenv("MES_RETURN_COST_MYSQL_USER"),System.getenv("MES_RETURN_COST_MYSQL_PASSWORD"));
        assertTrue(checked().queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",schema).isEmpty(),"Refusing a nonempty schema");
        fixture=new MesCostReturnServiceTest();fixture.setupDataSource(dataSource);
    }
    @AfterEach void clear() {
        TenantContextHolder.clear();if(dataSource==null)return;
        var jdbc=checked();var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",String.class,schema);
        assertTrue(TABLES.containsAll(tables),"Refusing cleanup of unexpected tables");
        for(String table:tables)jdbc.execute("DROP TABLE `"+table+"`");
    }
    private <T> T elsewhere(Callable<T> task) throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {return executor.submit(()->{TenantContextHolder.setTenantId(1L);try{return task.call();}finally{TenantContextHolder.clear();}}).get(10,TimeUnit.SECONDS);}
        finally {executor.shutdownNow();}
    }
    @Test void oldReadViewUsesCurrentReturnedCountersAndExactRemainingCost() {
        fixture.original(1,"3","1","1","1","FINAL");
        var template=new TransactionTemplate(new DataSourceTransactionManager(dataSource));template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status->{
            assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_source_balance",Integer.class));
            try {elsewhere(()->{fixture.service.recordTransaction(fixture.returnTransaction(200,"1",2000),fixture.stock(2000,"0"));return null;});}
            catch(Exception error){throw new RuntimeException(error);}
            assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_source_balance",Integer.class),"Old RR read view is established");
            fixture.service.recordTransaction(fixture.returnTransaction(201,"2",2001),fixture.stock(2001,"0"));
        });
        equal("3",fixture.jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
        equal("1",fixture.jdbc.queryForObject("SELECT SUM(material_amount) FROM mes_cost_return_allocation",BigDecimal.class));
        equal("0.66666667",fixture.jdbc.queryForObject("SELECT material_amount FROM mes_cost_return_allocation WHERE return_transaction_id=201",BigDecimal.class));
    }
    @Test void twoConcurrentPartialRestorationsCannotRestoreTheSameLastCapacity() throws Exception {
        fixture.original(1,"3","60","24","6","FINAL");
        var first=fixture.returnTransaction(200,"2",2000);var second=fixture.returnTransaction(201,"2",2001);
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var tasks=List.of(first,second).stream().map(tx->executor.submit(()->{
                TenantContextHolder.setTenantId(1L);
                try {gate.await();fixture.service.recordTransaction(tx,fixture.stock(tx.getMaterialStockId(),"0"));return true;}
                catch(ServiceException expected){return false;}
                finally {TenantContextHolder.clear();}
            })).toList();
            gate.countDown();int passed=0;for(var future:tasks)if(future.get(10,TimeUnit.SECONDS))passed++;
            assertEquals(1,passed);
        } finally {executor.shutdownNow();}
        equal("2",fixture.jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_allocation",Integer.class));
        equal("2",fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM mes_cost_stock_layer WHERE inbound_transaction_id IS NOT NULL",BigDecimal.class));
    }
    @Test void returnValuationDoesNotWaitForLocksOnOriginalShipmentOrCostAllocations() throws Exception {
        fixture.original(1,"3","60","24","6","FINAL");var tx=fixture.returnTransaction(200,"1",2000);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.executeQuery("SELECT id FROM mes_wm_product_sales WHERE id=10 FOR UPDATE").close();
                statement.executeQuery("SELECT id FROM mes_wm_transaction WHERE id=100 FOR UPDATE").close();
                statement.executeQuery("SELECT id FROM mes_cost_allocation WHERE id=1 FOR UPDATE").close();
            }
            try {
                elsewhere(()->{fixture.service.recordTransaction(tx,fixture.stock(2000,"0"));return null;});
                equal("1",fixture.jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
            } finally {connection.rollback();}
        }
    }
    @Test void ordinaryLayerDoesNotCreateAnEmptyBasisGapThatBlocksConcurrentReturn() throws Exception {
        fixture.original(1,"3","60","24","6","FINAL");var tx=fixture.returnTransaction(200,"1",2000);
        fixture.jdbc.update("INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,quantity,remaining_quantity,unit_cost,status) VALUES(2,1,3000,1,1,10,'FINAL')");
        var template=new TransactionTemplate(new DataSourceTransactionManager(dataSource));template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var executor=Executors.newSingleThreadExecutor();var completedWhileOrdinaryTransactionOpen=new AtomicBoolean();
        var future=new AtomicReference<Future<Void>>();
        try {
            template.executeWithoutResult(status->{
                var layer=fixture.jdbc.queryForList("SELECT * FROM mes_cost_stock_layer WHERE tenant_id=1 AND id=2 FOR UPDATE").get(0);
                assertNull(new MesReturnCostLedger(fixture.jdbc).allocateFromLayer(layer,BigDecimal.ONE));
                future.set(executor.submit(()->{
                    TenantContextHolder.setTenantId(1L);
                    try {fixture.service.recordTransaction(tx,fixture.stock(2000,"0"));return null;}
                    finally {TenantContextHolder.clear();}
                }));
                try {future.get().get(2,TimeUnit.SECONDS);completedWhileOrdinaryTransactionOpen.set(true);}
                catch(TimeoutException blockedByGap) { /* Release the transaction, join the worker, then assert. */ }
                catch(Exception error) {throw new RuntimeException(error);}
            });
            future.get().get(10,TimeUnit.SECONDS);
            assertTrue(completedWhileOrdinaryTransactionOpen.get(),"Ordinary stock must not hold an absent return-basis gap across inventory locks");
        } finally {executor.shutdownNow();}
    }
}
