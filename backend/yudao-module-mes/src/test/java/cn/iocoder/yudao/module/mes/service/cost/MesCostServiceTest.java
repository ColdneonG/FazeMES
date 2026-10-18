package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import javax.sql.DataSource;

/** Real H2 rows and transaction proxies; no deployed services or database is touched. */
class MesCostServiceTest {
    JdbcTemplate jdbc;
    MesCostService service;
    private static BigDecimal b(String n){return new BigDecimal(n);}
    private static void equal(String expected,BigDecimal actual){assertNotNull(actual);assertEquals(0,b(expected).compareTo(actual));}
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:cost"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");setupDataSource(ds);
    }
    void setupDataSource(DataSource ds) throws Exception {
        jdbc=new JdbcTemplate(ds);
        String migration=Files.readString(Path.of("../../database/migrations/20261018_broky_cost.sql"));
        migration=migration.substring(0,migration.indexOf("-- Cost permissions")).replace("b'0'","0");
        try(var conn=ds.getConnection()){ScriptUtils.executeSqlScript(conn,new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));}
        jdbc.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),name VARCHAR(100),item_type_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_md_item_type(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_or_product VARCHAR(20),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_md_product_bom(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,bom_item_id BIGINT,quantity DECIMAL(18,6),status INT DEFAULT 0,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY,tenant_id BIGINT,product_id BIGINT,quantity DECIMAL(18,6),status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_work_order_bom(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,item_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_task(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,process_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_pro_feedback(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,work_order_id BIGINT,biz_type INT,biz_id BIGINT,biz_line_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_produce_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,produce_id BIGINT,quality_status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,warehouse_id BIGINT,item_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_warehouse(id BIGINT PRIMARY KEY,tenant_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_item_receipt(id BIGINT PRIMARY KEY,tenant_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_item_receipt_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,receipt_id BIGINT,received_quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_sales(id BIGINT PRIMARY KEY,tenant_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,sales_id BIGINT,item_id BIGINT,quantity DECIMAL(18,6),deleted INT DEFAULT 0)");
        MesCostService target=new MesCostService();ReflectionTestUtils.setField(target,"jdbc",jdbc);
        ReflectionTestUtils.setField(target,"integrityService",org.mockito.Mockito.mock(cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.class));
        ProxyFactory pf=new ProxyFactory(target);pf.setProxyTargetClass(true);pf.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));service=(MesCostService)pf.getProxy();
        TenantContextHolder.setTenantId(1L);
        jdbc.update("INSERT INTO mes_md_item_type(id,tenant_id,item_or_product) VALUES(1,1,'PRODUCT'),(2,1,'ITEM')");
        jdbc.update("INSERT INTO mes_md_item(id,tenant_id,code,name,item_type_id) VALUES(1,1,'FAN','Fan',1),(2,1,'HEAD','Head',1),(3,1,'MOTOR','Motor',2),(4,1,'CASE','Case',2)");
        jdbc.update("INSERT INTO mes_md_product_bom(tenant_id,item_id,bom_item_id,quantity) VALUES(1,1,2,2),(1,1,4,1),(1,2,3,3)");
        profile(1,null,"299","4","2");profile(2,null,null,"2","1");profile(3,"10",null,null,null);profile(4,"5",null,null,null);
    }
    @AfterEach void clear(){TenantContextHolder.clear();}
    private void profile(long item,String purchase,String sale,String labor,String overhead) {
        Profile p=new Profile();p.setItemId(item);p.setPurchasePrice(purchase==null?null:b(purchase));p.setSalePrice(sale==null?null:b(sale));p.setLaborCost(labor==null?null:b(labor));p.setOverheadCost(overhead==null?null:b(overhead));service.saveProfile(p);
    }
    @Test void recursiveCostCountsLeafMaterialAndSubassemblyConversionOnce() {
        ProductCost cost=service.product(1L);assertEquals("COMPLETE",cost.getStatus());equal("65",cost.getMaterialCost());equal("8",cost.getLaborCost());equal("4",cost.getOverheadCost());equal("77",cost.getTotalCost());
        equal("6",cost.getLeaves().stream().filter(l->l.getItemId()==3L).findFirst().orElseThrow().getQuantity());
        jdbc.update("INSERT INTO mes_md_product_bom(tenant_id,item_id,bom_item_id,quantity,status) VALUES(1,1,3,1000,1)");equal("77",service.product(1L).getTotalCost());
    }
    @Test void missingPricesManufacturedLeafCyclesAndTenantAccessAreExplicit() {
        profile(3,null,null,null,null);assertEquals("INCOMPLETE",service.product(1L).getStatus());assertNull(service.product(1L).getTotalCost());
        jdbc.update("DELETE FROM mes_md_product_bom WHERE item_id=2");profile(2,"999",null,"2","1");assertNull(service.product(2L).getTotalCost());
        jdbc.update("INSERT INTO mes_md_product_bom(tenant_id,item_id,bom_item_id,quantity) VALUES(1,2,1,1)");assertThrows(ServiceException.class,()->service.product(1L));
        TenantContextHolder.setTenantId(2L);assertThrows(ServiceException.class,()->service.product(1L));assertThrows(ServiceException.class,()->service.profile(1L));
    }
    @Test void planAndHistoricalVersionsDoNotChangeWithNewPrices() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,1,10,1)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom(tenant_id,work_order_id,item_id,quantity) VALUES(1,10,2,20),(1,10,4,10)");service.capturePlan(10L);
        profile(3,"20",null,null,null);equal("137",service.product(1L).getTotalCost());
        WorkorderCost cost=service.workorderCost(10L);equal("77",cost.getPlanSnapshot().getTotalCost());equal("770",cost.getPlanTotalCost());assertEquals(2,service.profileHistory(3L).size());assertNull(cost.getActualUnitCost());
        service.capturePlan(10L);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_workorder",Integer.class));
    }
    @Test void planUsesEditedOrderBomRatherThanCurrentMasterBom() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,1,10,1)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom(tenant_id,work_order_id,item_id,quantity) VALUES(1,10,2,30),(1,10,4,10)");
        service.capturePlan(10L);equal("77",service.product(1L).getTotalCost());equal("110",service.workorderCost(10L).getPlanSnapshot().getTotalCost());
    }
    @Test void effortRetryIsIdempotentAndDifferentPayloadIsRejected() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,1,10,1)");jdbc.update("INSERT INTO mes_pro_task(id,tenant_id,work_order_id,process_id,status) VALUES(20,1,10,30,0)");
        Effort e=new Effort();e.setWorkorderId(10L);e.setProcessId(30L);e.setHours(b("2"));e.setHourlyRate(b("25"));e.setOverheadAmount(b("6"));e.setRemark("工时表#1");e.setRequestId("same-request");service.addEffort(e);service.addEffort(e);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_effort",Integer.class));e.setHours(b("3"));assertThrows(ServiceException.class,()->service.addEffort(e));
    }
    @Test void effortVoidKeepsAuditExcludesCostAndCannotBeReusedOrCrossTenant() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,1,10,1)");jdbc.update("INSERT INTO mes_pro_task(id,tenant_id,work_order_id,process_id,status) VALUES(20,1,10,30,0)");
        Effort e=new Effort();e.setWorkorderId(10L);e.setProcessId(30L);e.setHours(b("2"));e.setHourlyRate(b("25"));e.setOverheadAmount(b("6"));e.setRemark("工时表#1");e.setRequestId("void-request");service.addEffort(e);
        Long effortId=jdbc.queryForObject("SELECT id FROM mes_cost_effort",Long.class);VoidEffort cancel=new VoidEffort();cancel.setId(effortId);cancel.setReason("工时表重复登记，保留原记录");
        TenantContextHolder.setTenantId(2L);assertThrows(ServiceException.class,()->service.voidEffort(cancel));TenantContextHolder.setTenantId(1L);
        service.voidEffort(cancel);service.voidEffort(cancel);
        WorkorderCost result=service.workorderCost(10L);assertEquals(1,result.getEfforts().size());assertTrue(result.getEfforts().get(0).getVoided());assertEquals(cancel.getReason(),result.getEfforts().get(0).getVoidReason());assertNotNull(result.getEfforts().get(0).getVoidTime());assertNull(result.getActualLaborCost());
        assertThrows(ServiceException.class,()->service.addEffort(e));cancel.setReason("篡改撤销原因");assertThrows(ServiceException.class,()->service.voidEffort(cancel));
    }
    private MesWmTransactionDO tx(long txId,long stock,long item,String quantity,int biz,Long order,Long related,long line) {
        MesWmTransactionDO tx=new MesWmTransactionDO();tx.setId(txId);tx.setItemId(item);tx.setQuantity(b(quantity));tx.setBizType(biz);tx.setBizId(100L);tx.setBizLineId(line);tx.setWorkOrderId(order);tx.setRelatedTransactionId(related);tx.setMaterialStockId(stock);
        jdbc.update("INSERT INTO mes_wm_transaction(id,tenant_id,item_id,work_order_id,biz_type,biz_id,biz_line_id,quantity) VALUES(?,1,?,?,?,?,?,?)",txId,item,order,biz,100L,line,b(quantity));
        if(biz==MesBizTypeConstants.WM_PRODUCT_PRODUCE && jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_produce_line WHERE id=?",Integer.class,line)==0)jdbc.update("INSERT INTO mes_wm_product_produce_line(id,tenant_id,item_id,produce_id,quality_status) VALUES(?,1,?,100,1)",line,item);
        return tx;
    }
    private MesWmMaterialStockDO stock(long stock,String quantity){MesWmMaterialStockDO s=new MesWmMaterialStockDO();s.setId(stock);s.setQuantity(b(quantity));return s;}
    @Test void fifoTransferPreservesActualCostAndUnknownLegacyInventoryNeverUsesReferencePrice() {
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price) VALUES(1,'RECEIPT',91,12),(1,'RECEIPT',92,20)");
        service.recordTransaction(tx(1,50,3,"5",110,null,null,91),stock(50,"0"));service.recordTransaction(tx(2,50,3,"5",110,null,null,92),stock(50,"5"));
        service.recordTransaction(tx(3,50,3,"-7",115,10L,null,93),stock(50,"10"));
        equal("100",jdbc.queryForObject("SELECT SUM(quantity*unit_cost) FROM mes_cost_allocation WHERE transaction_id=3",BigDecimal.class));
        service.recordTransaction(tx(4,51,3,"7",115,10L,3L,93),stock(51,"0"));
        equal("100",jdbc.queryForObject("SELECT SUM(quantity*unit_cost) FROM mes_cost_stock_layer WHERE material_stock_id=51",BigDecimal.class));
        service.recordTransaction(tx(5,99,3,"-1",118,null,null,95),stock(99,"10"));
        assertNull(jdbc.queryForObject("SELECT unit_cost FROM mes_cost_allocation WHERE transaction_id=5",BigDecimal.class));
        assertEquals("INCOMPLETE",jdbc.queryForObject("SELECT status FROM mes_cost_allocation WHERE transaction_id=5",String.class));
    }
    @Test void salesZeroRevenueHasNoInventedMarginAndExecutedSnapshotIsImmutable() {
        jdbc.update("INSERT INTO mes_wm_product_sales(id,tenant_id,status) VALUES(100,1,0)");jdbc.update("INSERT INTO mes_wm_product_sales_line(id,tenant_id,sales_id,item_id,quantity) VALUES(95,1,100,3,1)");
        LinePrice p=new LinePrice();p.setLineId(95L);p.setUnitPrice(BigDecimal.ZERO);service.saveLinePrice("SALES",p);
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price) VALUES(1,'RECEIPT',91,12)");service.recordTransaction(tx(1,50,3,"1",110,null,null,91),stock(50,"0"));service.recordTransaction(tx(2,50,3,"-1",118,null,null,95),stock(50,"1"));
        Sale sale=service.sales(100L);assertEquals("COMPLETE",sale.getStatus());equal("0",sale.getSalesAmount());equal("12",sale.getCostAmount());equal("-12",sale.getGrossProfit());assertNull(sale.getGrossMargin());
        p.setUnitPrice(b("99"));assertThrows(ServiceException.class,()->service.saveLinePrice("SALES",p));
    }
    @Test void concurrentPriceVersionsAreSerialized() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        try {Callable<Void> save=()->{TenantContextHolder.setTenantId(1L);try{gate.await();profile(3,"11",null,null,null);return null;}finally{TenantContextHolder.clear();}};
            Future<Void> a=pool.submit(save),b=pool.submit(save);gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(3,service.profileHistory(3L).size());
        } finally {pool.shutdownNow();}
    }
    @Test void outputTransferredBeforeSettlementIsFinalizedAndSubassemblyCannotBeConsumedEarly() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,2,1,1),(11,1,1,1,1)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom(tenant_id,work_order_id,item_id,quantity) VALUES(1,10,3,3)");
        jdbc.update("INSERT INTO mes_pro_task(id,tenant_id,work_order_id,process_id,status) VALUES(20,1,10,30,4)");service.capturePlan(10L);
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price) VALUES(1,'RECEIPT',91,10)");
        service.recordTransaction(tx(1,50,3,"3",110,null,null,91),stock(50,"0"));
        service.recordTransaction(tx(2,50,3,"-3",122,10L,null,92),stock(50,"3"));
        service.recordTransaction(tx(3,60,2,"1",123,10L,null,93),stock(60,"0"));
        service.recordTransaction(tx(4,60,2,"-1",117,10L,null,94),stock(60,"1"));
        service.recordTransaction(tx(5,61,2,"1",117,10L,4L,94),stock(61,"0"));
        MesWmTransactionDO early=tx(6,61,2,"-1",122,11L,null,95);
        assertThrows(ServiceException.class,()->service.recordTransaction(early,stock(61,"1")));
        equal("1",jdbc.queryForObject("SELECT remaining_quantity FROM mes_cost_stock_layer WHERE material_stock_id=61",BigDecimal.class));
        jdbc.update("DELETE FROM mes_wm_transaction WHERE id=6");
        Effort effort=new Effort();effort.setWorkorderId(10L);effort.setProcessId(30L);effort.setHours(b("2"));effort.setHourlyRate(b("2"));effort.setOverheadAmount(b("1"));effort.setRemark("实际工时登记");effort.setRequestId("settle-10");service.addEffort(effort);
        jdbc.update("UPDATE mes_pro_work_order SET status=2 WHERE id=10");service.settle(10L);service.settle(10L);
        equal("35",service.workorderCost(10L).getActualUnitCost());assertEquals("SETTLED",service.workorderCost(10L).getStatus());
        equal("35",jdbc.queryForObject("SELECT unit_cost FROM mes_cost_stock_layer WHERE material_stock_id=61",BigDecimal.class));
        assertThrows(ServiceException.class,()->service.addEffort(effort));
        VoidEffort cancel=new VoidEffort();cancel.setId(jdbc.queryForObject("SELECT id FROM mes_cost_effort WHERE workorder_id=10",Long.class));cancel.setReason("结算后禁止撤销");assertThrows(ServiceException.class,()->service.voidEffort(cancel));
        service.recordTransaction(tx(7,61,2,"-1",122,11L,null,96),stock(61,"1"));
        equal("30",jdbc.queryForObject("SELECT material_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));equal("4",jdbc.queryForObject("SELECT labor_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));equal("1",jdbc.queryForObject("SELECT overhead_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));
    }
    @Test void nonconformingOutputIsNotCountedAsGoodAndCannotBeSettled() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,2,1,1)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom(tenant_id,work_order_id,item_id,quantity) VALUES(1,10,3,3)");
        jdbc.update("INSERT INTO mes_pro_task(id,tenant_id,work_order_id,process_id,status) VALUES(20,1,10,30,4)");service.capturePlan(10L);
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price) VALUES(1,'RECEIPT',91,10)");
        service.recordTransaction(tx(1,50,3,"3",110,null,null,91),stock(50,"0"));service.recordTransaction(tx(2,50,3,"-3",122,10L,null,92),stock(50,"3"));
        service.recordTransaction(tx(3,60,2,"1",123,10L,null,93),stock(60,"0"));service.recordTransaction(tx(4,61,2,"1",123,10L,null,94),stock(61,"0"));
        jdbc.update("UPDATE mes_wm_product_produce_line SET quality_status=2 WHERE id=94");
        Effort e=new Effort();e.setWorkorderId(10L);e.setProcessId(30L);e.setHours(b("1"));e.setHourlyRate(b("2"));e.setOverheadAmount(b("1"));e.setRemark("实际工时");e.setRequestId("bad-output");service.addEffort(e);
        jdbc.update("UPDATE mes_pro_work_order SET status=2 WHERE id=10");
        WorkorderCost c=service.workorderCost(10L);equal("1",c.getProducedQuantity());assertNull(c.getActualUnitCost());assertTrue(c.getIssues().stream().anyMatch(s->s.contains("不良产出")));assertThrows(ServiceException.class,()->service.settle(10L));
        assertEquals("PROVISIONAL",jdbc.queryForObject("SELECT status FROM mes_cost_workorder WHERE workorder_id=10",String.class));
    }
    private TransactionTemplate repeatableRead() {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;
    }
    @Test void repeatingFractionRoundingNeverInventsNegativeOverhead() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(11,1,1,1,1)");
        jdbc.update("INSERT INTO mes_cost_workorder(tenant_id,workorder_id,plan_snapshot,plan_quantity,status,actual_material_cost,actual_labor_cost,actual_overhead_cost,actual_total_cost,produced_quantity,actual_unit_cost) VALUES(1,10,'{}',3,'SETTLED',0.5,0.5,0,1,3,0.33333333)");
        jdbc.update("INSERT INTO mes_cost_stock_layer(tenant_id,material_stock_id,origin_workorder_id,quantity,remaining_quantity,unit_cost,status) VALUES(1,61,10,3,3,0.33333333,'FINAL')");
        service.recordTransaction(tx(7,61,2,"-1",122,11L,null,96),stock(61,"3"));
        equal("0",jdbc.queryForObject("SELECT overhead_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));
        equal("0.33333333",jdbc.queryForObject("SELECT material_amount+labor_amount+overhead_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_allocation WHERE material_amount<0 OR labor_amount<0 OR overhead_amount<0",Integer.class));
    }
    private void elsewhere(Runnable work) {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{worker.submit(()->{TenantContextHolder.setTenantId(1L);try{repeatableRead().executeWithoutResult(s->work.run());}finally{TenantContextHolder.clear();}}).get(10,TimeUnit.SECONDS);}
        catch(Exception e){throw new AssertionError(e);}finally{worker.shutdownNow();}
    }
    // Invoked only by MesCostMySqlConcurrencyTest: H2 RR does not implement InnoDB current reads.
    void staleInventoryReadViewCannotCreateAnExtraUnknownCostLayer() {
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price) VALUES(1,'RECEIPT',91,12),(1,'RECEIPT',92,20)");
        service.recordTransaction(tx(1,50,3,"5",110,null,null,91),stock(50,"0"));
        repeatableRead().executeWithoutResult(outer->{
            equal("5",jdbc.queryForObject("SELECT SUM(remaining_quantity) FROM mes_cost_stock_layer WHERE material_stock_id=50",BigDecimal.class));
            elsewhere(()->service.recordTransaction(tx(2,50,3,"5",110,null,null,92),stock(50,"5")));
            equal("5",jdbc.queryForObject("SELECT SUM(remaining_quantity) FROM mes_cost_stock_layer WHERE material_stock_id=50",BigDecimal.class));
            // The stock service returns its locked current quantity, while this outer read view predates receipt #2.
            service.recordTransaction(tx(3,50,3,"-7",118,null,null,93),stock(50,"10"));
        });
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer WHERE material_stock_id=50",Integer.class));
        equal("3",jdbc.queryForObject("SELECT SUM(remaining_quantity) FROM mes_cost_stock_layer WHERE material_stock_id=50",BigDecimal.class));
        equal("100",jdbc.queryForObject("SELECT SUM(quantity*unit_cost) FROM mes_cost_allocation WHERE transaction_id=3",BigDecimal.class));
    }
    void aSettledLayerAndItsOriginAreReadFromTheSameCurrentState() {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,product_id,quantity,status) VALUES(10,1,2,1,2),(11,1,1,1,1)");
        jdbc.update("INSERT INTO mes_cost_workorder(tenant_id,workorder_id,plan_snapshot,plan_quantity,status) VALUES(1,10,'{}',1,'PROVISIONAL')");
        jdbc.update("INSERT INTO mes_cost_stock_layer(tenant_id,material_stock_id,origin_workorder_id,quantity,remaining_quantity,status) VALUES(1,61,10,1,1,'PROVISIONAL')");
        repeatableRead().executeWithoutResult(outer->{
            assertEquals("PROVISIONAL",jdbc.queryForObject("SELECT status FROM mes_cost_workorder WHERE workorder_id=10",String.class));
            elsewhere(()->{jdbc.update("UPDATE mes_cost_workorder SET status='SETTLED',actual_material_cost=30,actual_labor_cost=4,actual_overhead_cost=1,actual_total_cost=35,produced_quantity=1,actual_unit_cost=35 WHERE workorder_id=10");jdbc.update("UPDATE mes_cost_stock_layer SET status='FINAL',unit_cost=35 WHERE origin_workorder_id=10");});
            assertEquals("PROVISIONAL",jdbc.queryForObject("SELECT status FROM mes_cost_workorder WHERE workorder_id=10",String.class));
            service.recordTransaction(tx(7,61,2,"-1",122,11L,null,96),stock(61,"1"));
        });
        equal("30",jdbc.queryForObject("SELECT material_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));equal("4",jdbc.queryForObject("SELECT labor_amount FROM mes_cost_allocation WHERE transaction_id=7",BigDecimal.class));
    }
}
