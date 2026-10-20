package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real cost rows and transactions; actual application data is never used. */
class MesCostReturnServiceTest {
    JdbcTemplate jdbc;
    MesCostService service;
    DataSource dataSource;
    static BigDecimal b(String value) { return new BigDecimal(value); }
    static void equal(String expected, BigDecimal actual) {
        assertNotNull(actual); assertEquals(0,b(expected).compareTo(actual),"expected " + expected + ", actual " + actual);
    }
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:return_cost_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        setupDataSource(ds);
    }
    void setupDataSource(DataSource ds) throws Exception {
        dataSource=ds; jdbc=new JdbcTemplate(ds);
        String base=Files.readString(Path.of("../../database/migrations/20261018_broky_cost.sql"));
        execute(base.substring(0,base.indexOf("-- Cost permissions")).replace("b'0'","0"));
        jdbc.execute("ALTER TABLE mes_cost_stock_layer ADD COLUMN return_basis_required BOOLEAN NOT NULL DEFAULT FALSE");
        String returns=Files.readString(Path.of("../../database/migrations/20261020_return_sales_cost.sql"));
        execute(returns.substring(0,returns.indexOf("-- Quantity widening:")));
        jdbc.execute("ALTER TABLE mes_cost_stock_layer MODIFY quantity DECIMAL(20,6) NOT NULL");
        jdbc.execute("ALTER TABLE mes_cost_stock_layer MODIFY remaining_quantity DECIMAL(20,6) NOT NULL");
        jdbc.execute("ALTER TABLE mes_cost_allocation MODIFY quantity DECIMAL(20,6) NOT NULL");
        jdbc.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),name VARCHAR(100),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_sales(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),client_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,sales_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),quality_status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_return_sales(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),client_id BIGINT,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_return_sales_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,return_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),origin_transaction_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT,biz_type INT,type INT,biz_id BIGINT,biz_code VARCHAR(64),biz_line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),material_stock_id BIGINT,return_source_transaction_id BIGINT,deleted INT DEFAULT 0)");
        MesCostService target=new MesCostService(); ReflectionTestUtils.setField(target,"jdbc",jdbc);
        ReflectionTestUtils.setField(target,"integrityService",mock(MesProductionIntegrityService.class));
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(MesCostService)proxy.getProxy(); TenantContextHolder.setTenantId(1L);
        jdbc.update("INSERT INTO mes_md_item VALUES(1,1,'BROKY-FG','Finished product',0)");
        jdbc.update("INSERT INTO mes_wm_product_sales VALUES(10,1,'SALE-10',7,4,0)");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,tenant_id,sales_id,item_id,quantity,batch_id,batch_code) VALUES(11,1,10,1,3,9,'BATCH-A')");
        jdbc.update("INSERT INTO mes_wm_return_sales VALUES(20,1,'RETURN-20',7,3,0)");
        jdbc.update("INSERT INTO mes_wm_return_sales_line VALUES(21,1,20,1,3,9,'BATCH-A',100,0)");
        jdbc.update("INSERT INTO mes_wm_transaction VALUES(100,1,118,2,10,'SALE-10',11,1,-3,9,'BATCH-A',1000,NULL,0)");
    }
    void execute(String sql) throws Exception {
        try(var connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    void original(long allocationId,String quantity,String material,String labor,String overhead,String status) {
        BigDecimal q=b(quantity), unit=material==null||labor==null||overhead==null?null:b(material).add(b(labor)).add(b(overhead)).divide(q,8,RoundingMode.HALF_UP);
        jdbc.update("INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,quantity,remaining_quantity,unit_cost,status) VALUES(?,1,1000,?,0,?,?)",allocationId,q,unit,status);
        jdbc.update("INSERT INTO mes_cost_allocation(id,tenant_id,transaction_id,layer_id,quantity,unit_cost,status,material_amount,labor_amount,overhead_amount) VALUES(?,1,100,?,?,?,?,?,?,?)",allocationId,allocationId,q,unit,status,material==null?null:b(material),labor==null?null:b(labor),overhead==null?null:b(overhead));
    }
    MesWmTransactionDO returnTransaction(long id,String quantity,long stockId) {
        return transaction(id,119,20,21,quantity,stockId,100L,null);
    }
    MesWmTransactionDO transaction(long id,int biz,long doc,long line,String quantity,long stockId,Long source,Long related) {
        jdbc.update("INSERT INTO mes_wm_transaction VALUES(?,1,?,?,?,'DOC',?,1,?,9,'BATCH-A',?,?,0)",id,biz,b(quantity).signum()>0?1:2,doc,line,b(quantity),stockId,source);
        return MesWmTransactionDO.builder().id(id).bizType(biz).bizId(doc).bizLineId(line).itemId(1L)
                .batchId(9L).batchCode("BATCH-A").quantity(b(quantity)).materialStockId(stockId)
                .returnSourceTransactionId(source).relatedTransactionId(related).build();
    }
    MesWmMaterialStockDO stock(long id,String quantity) {
        var stock=new MesWmMaterialStockDO();stock.setId(id);stock.setQuantity(b(quantity));return stock;
    }
    @Test void actualPartialReturnRestoresImmutableOriginalCostInsteadOfUnknownOrFullQuantity() {
        original(1,"3","60","24","6","FINAL");
        service.recordTransaction(returnTransaction(200,"1",2000),stock(2000,"0"));
        equal("30",jdbc.queryForObject("SELECT unit_cost FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
        equal("1",jdbc.queryForObject("SELECT SUM(quantity) FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
        equal("20",jdbc.queryForObject("SELECT material_amount FROM mes_cost_return_allocation WHERE return_transaction_id=200",BigDecimal.class));
        equal("8",jdbc.queryForObject("SELECT labor_amount FROM mes_cost_return_allocation WHERE return_transaction_id=200",BigDecimal.class));
        equal("2",jdbc.queryForObject("SELECT overhead_amount FROM mes_cost_return_allocation WHERE return_transaction_id=200",BigDecimal.class));
    }
    @Test void multipleOriginalFifoPortionsAreRestoredPartiallyWithoutChangingOriginalEvidence() {
        original(1,"1","10","2","1","FINAL");original(2,"2","40","4","2","FINAL");
        var original=jdbc.queryForList("SELECT * FROM mes_cost_allocation ORDER BY id");
        jdbc.update("UPDATE mes_cost_stock_layer SET unit_cost=999,status='PROVISIONAL'");
        service.recordTransaction(returnTransaction(200,"1.5",2000),stock(2000,"0"));
        equal("24.5",jdbc.queryForObject("SELECT SUM(material_amount+labor_amount+overhead_amount) FROM mes_cost_return_allocation WHERE return_transaction_id=200",BigDecimal.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_allocation WHERE return_transaction_id=200",Integer.class));
        service.recordTransaction(returnTransaction(201,"1.5",2001),stock(2001,"0"));
        equal("59",jdbc.queryForObject("SELECT SUM(material_amount+labor_amount+overhead_amount) FROM mes_cost_return_allocation",BigDecimal.class));
        equal("3",jdbc.queryForObject("SELECT SUM(returned_quantity) FROM mes_cost_return_source_balance",BigDecimal.class));
        assertEquals(original,jdbc.queryForList("SELECT * FROM mes_cost_allocation ORDER BY id"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer WHERE inbound_transaction_id IS NOT NULL AND origin_workorder_id IS NOT NULL",Integer.class));
    }
    @Test void cumulativeRoundingRestoresExactFinalRemainderForEveryComponent() {
        original(1,"3","1","1","1","FINAL");
        for(int i=0;i<3;i++)service.recordTransaction(returnTransaction(200+i,"1",2000),stock(2000,Integer.toString(i)));
        var amounts=jdbc.queryForList("SELECT material_amount FROM mes_cost_return_allocation ORDER BY return_transaction_id",BigDecimal.class);
        equal("0.33333333",amounts.get(0));equal("0.33333334",amounts.get(1));equal("0.33333333",amounts.get(2));
        equal("1",jdbc.queryForObject("SELECT SUM(material_amount) FROM mes_cost_return_allocation",BigDecimal.class));
        equal("1",jdbc.queryForObject("SELECT SUM(labor_amount) FROM mes_cost_return_allocation",BigDecimal.class));
        equal("1",jdbc.queryForObject("SELECT SUM(overhead_amount) FROM mes_cost_return_allocation",BigDecimal.class));
    }
    @Test void returnedCostSurvivesTransfersAndPartialResalesWithExactComponentTotals() {
        original(1,"3","1","2","3","FINAL");
        service.recordTransaction(returnTransaction(200,"3",2000),stock(2000,"0"));
        service.recordTransaction(transaction(201,111,30,31,"-3",2000,null,null),stock(2000,"3"));
        // The already recorded outgoing parts must prevail over any later layer metadata.
        jdbc.update("UPDATE mes_cost_stock_layer SET unit_cost=999,status='INCOMPLETE' WHERE inbound_transaction_id=200");
        service.recordTransaction(transaction(202,112,30,31,"3",3000,null,201L),stock(3000,"0"));
        service.recordTransaction(transaction(203,118,40,41,"-1",3000,null,null),stock(3000,"3"));
        service.recordTransaction(transaction(204,111,50,51,"-2",3000,null,null),stock(3000,"2"));
        service.recordTransaction(transaction(205,112,50,51,"2",4000,null,204L),stock(4000,"0"));
        service.recordTransaction(transaction(206,118,60,61,"-1",4000,null,null),stock(4000,"2"));
        service.recordTransaction(transaction(207,118,70,71,"-1",4000,null,null),stock(4000,"1"));
        equal("1",jdbc.queryForObject("SELECT SUM(material_amount) FROM mes_cost_allocation WHERE transaction_id IN(203,206,207)",BigDecimal.class));
        equal("2",jdbc.queryForObject("SELECT SUM(labor_amount) FROM mes_cost_allocation WHERE transaction_id IN(203,206,207)",BigDecimal.class));
        equal("3",jdbc.queryForObject("SELECT SUM(overhead_amount) FROM mes_cost_allocation WHERE transaction_id IN(203,206,207)",BigDecimal.class));
        equal("0",jdbc.queryForObject("SELECT SUM(remaining_quantity) FROM mes_cost_stock_layer WHERE inbound_transaction_id IN(202,205)",BigDecimal.class));
        equal("3",jdbc.queryForObject("SELECT quantity FROM mes_cost_allocation WHERE transaction_id=100",BigDecimal.class));
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong_quantity","provisional","null_component"})
    void missingOriginalCostStaysUnknownAfterLaterRepricingAndResale(String gap) {
        if(!"missing".equals(gap))original(1,"wrong_quantity".equals(gap)?"2":"3","null_component".equals(gap)?null:"60","24","6","provisional".equals(gap)?"PROVISIONAL":"FINAL");
        jdbc.update("UPDATE mes_cost_stock_layer SET origin_workorder_id=99");
        service.recordTransaction(returnTransaction(200,"1",2000),stock(2000,"0"));
        assertEquals("INCOMPLETE",jdbc.queryForObject("SELECT status FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",String.class));
        assertNull(jdbc.queryForObject("SELECT unit_cost FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
        assertNull(jdbc.queryForObject("SELECT origin_workorder_id FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",Long.class));
        // This is the same origin-scoped update performed by actual work-order settlement.
        jdbc.update("UPDATE mes_cost_stock_layer SET unit_cost=999,status='FINAL' WHERE origin_workorder_id=99");
        service.recordTransaction(transaction(201,118,30,31,"-1",2000,null,null),stock(2000,"1"));
        assertEquals("INCOMPLETE",jdbc.queryForObject("SELECT status FROM mes_cost_allocation WHERE transaction_id=201",String.class));
        assertNull(jdbc.queryForObject("SELECT material_amount FROM mes_cost_allocation WHERE transaction_id=201",BigDecimal.class));
        assertNull(jdbc.queryForObject("SELECT labor_amount FROM mes_cost_allocation WHERE transaction_id=201",BigDecimal.class));
        assertNull(jdbc.queryForObject("SELECT overhead_amount FROM mes_cost_allocation WHERE transaction_id=201",BigDecimal.class));
    }
    @Test void repeatedIdenticalPostingIsIdempotentButCannotRestoreMoreThanOriginal() {
        original(1,"3","60","24","6","FINAL");
        var tx=returnTransaction(200,"2",2000);service.recordTransaction(tx,stock(2000,"0"));
        service.recordTransaction(tx,stock(2000,"0"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_allocation",Integer.class));
        equal("2",jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
        var excess=returnTransaction(201,"2",2001);
        assertThrows(ServiceException.class,()->service.recordTransaction(excess,stock(2001,"0")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer WHERE inbound_transaction_id=201",Integer.class));
        equal("2",jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
        tx.setQuantity(b("1"));assertThrows(ServiceException.class,()->service.recordTransaction(tx,stock(2000,"0")));
    }
    @ParameterizedTest @ValueSource(strings={"customer","item","batch","line_origin","tenant"})
    void sourceIdentityCannotBeForged(String dimension) {
        original(1,"3","60","24","6","FINAL");
        var tx=returnTransaction(200,"1",2000);
        switch(dimension) {
            case "customer" -> jdbc.update("UPDATE mes_wm_return_sales SET client_id=8");
            case "item" -> jdbc.update("UPDATE mes_wm_return_sales_line SET item_id=2");
            case "batch" -> jdbc.update("UPDATE mes_wm_return_sales_line SET batch_id=10");
            case "line_origin" -> jdbc.update("UPDATE mes_wm_return_sales_line SET origin_transaction_id=101");
            case "tenant" -> TenantContextHolder.setTenantId(2L);
        }
        assertThrows(ServiceException.class,()->service.recordTransaction(tx,stock(2000,"0")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_source_balance",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_allocation",Integer.class));
    }
    @Test void lateConstraintFailureRollsBackEveryCostCounterAuditLayerAndBasis() {
        original(1,"1","10","2","1","FINAL");original(2,"2","40","4","2","FINAL");
        jdbc.execute("ALTER TABLE mes_cost_layer_basis ADD CONSTRAINT injected_failure CHECK(material_amount<20)");
        var tx=returnTransaction(200,"3",2000);
        assertThrows(RuntimeException.class,()->service.recordTransaction(tx,stock(2000,"0")));
        for(String table:List.of("mes_cost_return_source_balance","mes_cost_return_allocation","mes_cost_layer_basis"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_allocation",Integer.class));
    }
    @Test void valuationSeparatesCurrentReturnOriginalShipmentAndCumulativeRemainingWithoutRepricing() throws Exception {
        original(1,"3","60","24","6","FINAL");
        jdbc.update("UPDATE mes_wm_return_sales_line SET quantity=1");
        service.recordTransaction(returnTransaction(200,"1",2000),stock(2000,"0"));
        jdbc.update("UPDATE mes_wm_return_sales SET status=4");
        jdbc.update("INSERT INTO mes_wm_return_sales VALUES(30,1,'RETURN-30',7,3,0)");
        jdbc.update("INSERT INTO mes_wm_return_sales_line VALUES(31,1,30,1,1,9,'BATCH-A',100,0)");
        service.recordTransaction(transaction(201,119,30,31,"1",2001,100L,null),stock(2001,"0"));
        jdbc.update("UPDATE mes_wm_return_sales SET status=4 WHERE id=30");
        var result=service.returnSales(20L);assertEquals("COMPLETE",result.getStatus());
        equal("30",result.getCostAmount());equal("20",result.getMaterialAmount());equal("8",result.getLaborAmount());equal("2",result.getOverheadAmount());
        var source=result.getOriginals().get(0);equal("90",source.getOriginalCostAmount());equal("60",source.getReturnedCostAmount());equal("30",source.getRemainingCostAmount());
        equal("2",source.getReturnedQuantity());equal("1",source.getRemainingQuantity());
        var json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        assertTrue(json.get("costAmount").isTextual());assertTrue(json.get("lines").get(0).get("quantity").isTextual());
        TenantContextHolder.setTenantId(2L);assertThrows(ServiceException.class,()->service.returnSales(20L));
    }
    @Test void draftAndUnprovenHistoricalReturnsHaveNullValuationRatherThanZero() {
        original(1,"3","60","24","6","FINAL");
        var draft=service.returnSales(20L);assertEquals("INCOMPLETE",draft.getStatus());assertFalse(draft.getPosted());assertNull(draft.getCostAmount());
        equal("0",draft.getLines().get(0).getPostedQuantity());
        jdbc.update("UPDATE mes_wm_return_sales SET status=4");jdbc.update("UPDATE mes_wm_return_sales_line SET origin_transaction_id=NULL");
        var historical=service.returnSales(20L);assertTrue(historical.getPosted());assertNull(historical.getCostAmount());assertFalse(historical.getLines().get(0).getIssues().isEmpty());
    }
    @Test void legitimateZeroOriginalCostIsFinalAndDoesNotTurnIntoUnknown() {
        original(1,"3","0","0","0","FINAL");
        service.recordTransaction(returnTransaction(200,"3",2000),stock(2000,"0"));
        equal("0",jdbc.queryForObject("SELECT material_amount+labor_amount+overhead_amount FROM mes_cost_return_allocation",BigDecimal.class));
        assertEquals("FINAL",jdbc.queryForObject("SELECT status FROM mes_cost_return_allocation",String.class));
    }
    @Test void otherBusinessTypesCannotUseSalesReturnSourceAndProductionReturnPathRemainsGeneric() {
        original(1,"3","60","24","6","FINAL");
        var forbidden=transaction(200,116,20,21,"1",2000,100L,null);
        assertThrows(ServiceException.class,()->service.recordTransaction(forbidden,stock(2000,"0")));
        var paired=transaction(201,116,20,21,"3",2000,null,100L);
        service.recordTransaction(paired,stock(2000,"0"));
        equal("3",jdbc.queryForObject("SELECT SUM(quantity) FROM mes_cost_stock_layer WHERE inbound_transaction_id=201",BigDecimal.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_return_allocation",Integer.class));
    }
    @Test void fullWarehouseQuantityPrecisionIsRetainedEvenWithoutHistoricalCostEvidence() {
        String quantity="99999999999999.123456";
        jdbc.update("UPDATE mes_wm_transaction SET quantity=? WHERE id=100",b(quantity).negate());
        jdbc.update("UPDATE mes_wm_product_sales_line SET quantity=? WHERE id=11",b(quantity));
        jdbc.update("UPDATE mes_wm_return_sales_line SET quantity=? WHERE id=21",b(quantity));
        service.recordTransaction(returnTransaction(200,quantity,2000),stock(2000,"0"));
        equal(quantity,jdbc.queryForObject("SELECT quantity FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
        equal(quantity,jdbc.queryForObject("SELECT returned_quantity FROM mes_cost_return_source_balance",BigDecimal.class));
        assertNull(jdbc.queryForObject("SELECT unit_cost FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
    }
    @Test void partialReturnCannotCloneACompletePairedMovementAllocation() {
        original(1,"3","60","24","6","FINAL");
        var tx=returnTransaction(200,"1",2000);tx.setRelatedTransactionId(100L);
        assertThrows(ServiceException.class,()->service.recordTransaction(tx,stock(2000,"0")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",Integer.class));
    }
    @Test void aRequiredReturnBasisCannotDisappearAndBecomeOrdinaryMaterialCost() {
        original(1,"3","60","24","6","FINAL");
        service.recordTransaction(returnTransaction(200,"3",2000),stock(2000,"0"));
        assertTrue(jdbc.queryForObject("SELECT return_basis_required FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",Boolean.class));
        jdbc.update("DELETE FROM mes_cost_layer_basis");
        var tx=transaction(201,118,30,31,"-1",2000,null,null);
        assertThrows(ServiceException.class,()->service.recordTransaction(tx,stock(2000,"3")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_allocation WHERE transaction_id=201",Integer.class));
        equal("3",jdbc.queryForObject("SELECT remaining_quantity FROM mes_cost_stock_layer WHERE inbound_transaction_id=200",BigDecimal.class));
    }
    @Test void unattributedHistoricalReturnMakesCumulativeRemainingUnknownEvenWhenOriginalCostIsKnown() {
        original(1,"3","60","24","6","FINAL");
        jdbc.update("INSERT INTO mes_wm_return_sales VALUES(30,1,'OLD-RETURN',7,4,0)");
        jdbc.update("INSERT INTO mes_wm_return_sales_line VALUES(31,1,30,1,1,9,'BATCH-A',NULL,0)");
        transaction(201,119,30,31,"1",2001,null,null);
        jdbc.update("UPDATE mes_wm_transaction SET deleted=1 WHERE id=201");
        var source=service.returnSales(20L).getOriginals().get(0);
        equal("3",source.getOutboundQuantity());equal("90",source.getOriginalCostAmount());
        assertEquals("INCOMPLETE",source.getStatus());assertNull(source.getReturnedQuantity());assertNull(source.getRemainingQuantity());
        assertNull(source.getReturnedCostAmount());assertNull(source.getRemainingCostAmount());assertFalse(source.getIssues().isEmpty());
    }
}
