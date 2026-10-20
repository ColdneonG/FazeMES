package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.service.md.item.*;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MyBatis stock and ledger, real source budgets, real FIFO cost and one surrounding transaction. */
@Import({MesWmTransactionServiceImpl.class,MesWmMaterialStockServiceImpl.class,MesWmReturnSalesSourceService.class,
        MesWmReturnSalesDeliveryService.class,MesCostService.class,MesReturnPostingIntegrationTest.Database.class})
class MesReturnPostingIntegrationTest extends BaseDbUnitTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    static class Database { @Bean JdbcTemplate jdbcTemplate(DataSource source){return new JdbcTemplate(source);} }
    @Resource MesWmTransactionService transactions;
    @Resource JdbcTemplate jdbc;
    @Resource DataSource dataSource;
    @MockitoBean MesMdItemService items;
    @MockitoBean MesMdItemTypeService itemTypes;
    @MockitoBean MesWmBatchService batches;
    @MockitoBean MesWmWarehouseService warehouses;
    @MockitoBean MesWmWarehouseLocationService locations;
    @MockitoBean MesWmWarehouseAreaService areas;
    @MockitoBean MesProKittingService kitting;
    @MockitoBean MesProductionIntegrityService integrity;
    private static final List<String> COST_TABLES=List.of("mes_cost_layer_basis","mes_cost_return_allocation","mes_cost_return_source_balance",
            "mes_cost_allocation","mes_cost_stock_layer","mes_cost_line_price","mes_cost_effort","mes_cost_workorder","mes_cost_profile");
    @BeforeEach void fixture() throws Exception {
        TenantContextHolder.setTenantId(0L);
        String base=Files.readString(Path.of("../../database/migrations/20261018_broky_cost.sql"));
        execute(base.substring(0,base.indexOf("-- Cost permissions")).replace("b'0'","0"));
        String returns=Files.readString(Path.of("../../database/migrations/20261020_return_sales_cost.sql"));
        execute(returns.substring(0,returns.indexOf("-- Quantity widening:")));
        jdbc.execute("ALTER TABLE mes_cost_stock_layer ADD COLUMN IF NOT EXISTS return_basis_required BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("ALTER TABLE mes_cost_stock_layer MODIFY quantity DECIMAL(20,6) NOT NULL");
        jdbc.execute("ALTER TABLE mes_cost_stock_layer MODIFY remaining_quantity DECIMAL(20,6) NOT NULL");
        jdbc.execute("ALTER TABLE mes_cost_allocation MODIFY quantity DECIMAL(20,6) NOT NULL");
        for(String table:COST_TABLES)jdbc.update("DELETE FROM "+table);
        clearOwnReturnRows();
        String audit=",creator VARCHAR(64) DEFAULT '',create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updater VARCHAR(64) DEFAULT '',update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,deleted BOOLEAN DEFAULT FALSE,tenant_id BIGINT DEFAULT 0";
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_wm_warehouse(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),frozen BOOLEAN DEFAULT FALSE"+audit+")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_wm_warehouse_location(id BIGINT PRIMARY KEY,warehouse_id BIGINT,name VARCHAR(255),frozen BOOLEAN DEFAULT FALSE"+audit+")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_wm_warehouse_area(id BIGINT PRIMARY KEY,location_id BIGINT,code VARCHAR(128),name VARCHAR(255),status INT DEFAULT 0,frozen BOOLEAN DEFAULT FALSE,allow_item_mixing BOOLEAN DEFAULT TRUE,allow_batch_mixing BOOLEAN DEFAULT TRUE"+audit+")");
        jdbc.execute("ALTER TABLE mes_wm_warehouse ADD COLUMN IF NOT EXISTS code VARCHAR(128)");
        jdbc.execute("ALTER TABLE mes_wm_warehouse ADD COLUMN IF NOT EXISTS frozen BOOLEAN DEFAULT FALSE");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_location ADD COLUMN IF NOT EXISTS warehouse_id BIGINT");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_location ADD COLUMN IF NOT EXISTS frozen BOOLEAN DEFAULT FALSE");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_area ADD COLUMN IF NOT EXISTS location_id BIGINT");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_area ADD COLUMN IF NOT EXISTS frozen BOOLEAN DEFAULT FALSE");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_area ADD COLUMN IF NOT EXISTS allow_item_mixing BOOLEAN DEFAULT TRUE");
        jdbc.execute("ALTER TABLE mes_wm_warehouse_area ADD COLUMN IF NOT EXISTS allow_batch_mixing BOOLEAN DEFAULT TRUE");
        jdbc.execute("ALTER TABLE mes_wm_transaction ADD COLUMN IF NOT EXISTS erp_time TIMESTAMP");
        jdbc.execute("ALTER TABLE mes_wm_transaction ADD COLUMN IF NOT EXISTS receipt_time TIMESTAMP");
        jdbc.update("DELETE FROM mes_wm_warehouse_area");jdbc.update("DELETE FROM mes_wm_warehouse_location");jdbc.update("DELETE FROM mes_wm_warehouse");
        jdbc.update("INSERT INTO mes_wm_warehouse(id,code) VALUES(2,'FG-RETURN'),(5,'FG-ORIGINAL')");
        jdbc.update("INSERT INTO mes_wm_warehouse_location(id,warehouse_id) VALUES(3,2),(6,5)");
        jdbc.update("INSERT INTO mes_wm_warehouse_area(id,location_id) VALUES(4,3),(7,6)");
        var item=new MesMdItemDO().setId(7L).setItemTypeId(1L).setBatchFlag(true);
        when(items.validateItemExists(7L)).thenReturn(item);when(items.validateItemExistsAndEnable(7L)).thenReturn(item);
        var batch=new MesWmBatchDO().setId(9L).setItemId(7L).setCode("BROKY-B9");
        when(batches.validateBatchExists(9L,7L)).thenReturn(batch);when(batches.getBatch(9L)).thenReturn(batch);
        when(warehouses.validateWarehouseExists(anyLong())).thenAnswer(call->new MesWmWarehouseDO().setId(call.getArgument(0)).setCode("FG").setFrozen(false));
        when(locations.validateWarehouseLocationExists(anyLong())).thenAnswer(call->new MesWmWarehouseLocationDO().setId(call.getArgument(0)).setFrozen(false));
        when(areas.validateWarehouseAreaExists(anyLong())).thenAnswer(call->new MesWmWarehouseAreaDO().setId(call.getArgument(0)).setFrozen(false).setAllowItemMixing(true).setAllowBatchMixing(true));
        jdbc.update("INSERT INTO mes_wm_batch(id,code,item_id,client_id) VALUES(9,'BROKY-B9',7,NULL)");
        jdbc.update("INSERT INTO mes_wm_product_sales(id,code,client_id,status) VALUES(20,'SALE-20',10,4)");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,item_id,quantity,batch_id,batch_code,quality_status,oqc_check_flag) VALUES(21,20,7,3,9,'BROKY-B9',1,FALSE)");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(22,20,21,7,3,9,'BROKY-B9',5,6,7,8)");
        jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_code,biz_line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(23,2,118,20,'SALE-20',21,7,-3,9,'BROKY-B9',5,6,7,8)");
        jdbc.update("INSERT INTO mes_wm_return_sales(id,code,name,client_id,status) VALUES(100,'RETURN-100','Actual return',10,3)");
        jdbc.update("INSERT INTO mes_wm_return_sales_line(id,return_id,origin_transaction_id,item_id,quantity,batch_id,batch_code,rqc_check_flag,quality_status) VALUES(101,100,23,7,1,9,'BROKY-B9',FALSE,1)");
        jdbc.update("INSERT INTO mes_wm_return_sales_detail(id,return_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id) VALUES(102,100,101,7,1,9,'BROKY-B9',2,3,4)");
        jdbc.update("INSERT INTO mes_wm_return_source_budget(tenant_id,origin_transaction_id,reserved_quantity,returned_quantity) VALUES(0,23,1,0)");
        jdbc.update("INSERT INTO mes_wm_return_source_reservation(tenant_id,return_line_id,return_id,origin_transaction_id,quantity,posted_quantity,state) VALUES(0,101,100,23,1,0,'RESERVED')");
        jdbc.update("INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,quantity,remaining_quantity,unit_cost,status) VALUES(1,0,8,3,0,30,'FINAL')");
        jdbc.update("INSERT INTO mes_cost_allocation(id,tenant_id,transaction_id,layer_id,quantity,unit_cost,status,material_amount,labor_amount,overhead_amount) VALUES(1,0,23,1,3,30,'FINAL',60,24,6)");
    }
    private void execute(String sql) throws Exception {
        try(var connection=dataSource.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
    }
    private void clearOwnReturnRows(){
        // BaseDb's shared clean.sql does not clear return_sales_line. Own every fixture row explicitly.
        jdbc.update("DELETE FROM mes_wm_return_posted_evidence WHERE tenant_id=0 AND return_id=100");
        jdbc.update("DELETE FROM mes_wm_return_source_reservation WHERE tenant_id=0 AND return_id=100");
        jdbc.update("DELETE FROM mes_wm_return_sales_detail WHERE tenant_id=0 AND return_id=100");
        jdbc.update("DELETE FROM mes_wm_return_sales_line WHERE tenant_id=0 AND return_id=100");
        jdbc.update("DELETE FROM mes_wm_return_sales WHERE tenant_id=0 AND id=100");
        jdbc.update("DELETE FROM mes_wm_return_source_budget WHERE tenant_id=0 AND origin_transaction_id=23");
    }
    @AfterEach void clearCostRows(){clearOwnReturnRows();for(String table:COST_TABLES)jdbc.update("DELETE FROM "+table);TenantContextHolder.clear();}
    private MesWmTransactionSaveReqDTO request() {
        return new MesWmTransactionSaveReqDTO().setType(1).setBizType(119).setBizId(100L).setBizCode("RETURN-100")
                .setBizLineId(101L).setReturnSourceTransactionId(23L).setReturnDetailId(102L).setItemId(7L)
                .setBatchId(9L).setBatchCode("BROKY-B9").setWarehouseId(2L).setLocationId(3L).setAreaId(4L).setQuantity(BigDecimal.ONE);
    }
    private BigDecimal decimal(String sql){return jdbc.queryForObject(sql,BigDecimal.class);}
    private void equal(String expected,String sql){assertEquals(0,new BigDecimal(expected).compareTo(decimal(sql)),sql);}
    @Test void realPartialPostingAtomicallyIncreasesStockRecordsOriginCountersAndRestoresThreeCostParts() {
        transactions.createTransactionList(List.of(request()));
        equal("1","SELECT quantity FROM mes_wm_material_stock WHERE item_id=7 AND warehouse_id=2");
        var posted=jdbc.queryForMap("SELECT * FROM mes_wm_transaction WHERE biz_type=119");
        assertEquals(23L,((Number)posted.get("return_source_transaction_id")).longValue());
        assertEquals(102L,((Number)posted.get("return_detail_id")).longValue());
        equal("0","SELECT reserved_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23");
        equal("1","SELECT returned_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23");
        equal("1","SELECT posted_quantity FROM mes_wm_return_source_reservation WHERE return_line_id=101");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_posted_evidence",Integer.class));
        equal("20","SELECT material_amount FROM mes_cost_return_allocation");
        equal("8","SELECT labor_amount FROM mes_cost_return_allocation");
        equal("2","SELECT overhead_amount FROM mes_cost_return_allocation");
        equal("1","SELECT basis_quantity FROM mes_cost_layer_basis");
        equal("1","SELECT returned_quantity FROM mes_cost_return_source_balance");
        equal("-3","SELECT quantity FROM mes_wm_transaction WHERE id=23");
        equal("90","SELECT material_amount+labor_amount+overhead_amount FROM mes_cost_allocation WHERE transaction_id=23");
        assertThrows(ServiceException.class,()->transactions.createTransactionList(List.of(request())));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction WHERE biz_type=119",Integer.class));
    }
    @Test void realCostSourceMismatchRollsBackNewStockLedgerBusinessCountersEvidenceAndEveryCostWrite() {
        jdbc.update("INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,original_allocation_id,source_quantity,source_status,source_unit_cost,source_material_amount,source_labor_amount,source_overhead_amount,returned_material_amount,returned_labor_amount,returned_overhead_amount) VALUES(0,23,1,1,2,'FINAL',30,60,24,6,0,0,0)");
        ServiceException failure=assertThrows(ServiceException.class,()->transactions.createTransactionList(List.of(request())));
        assertTrue(failure.getMessage().contains("原销售成本依据发生变化"),"Failure must occur at real cost restoration after posting, not at a fixture guard");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_material_stock WHERE warehouse_id=2",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction WHERE biz_type=119",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_posted_evidence",Integer.class));
        equal("1","SELECT reserved_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23");
        equal("0","SELECT returned_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23");
        equal("0","SELECT posted_quantity FROM mes_wm_return_source_reservation WHERE return_line_id=101");
        assertEquals("RESERVED",jdbc.queryForObject("SELECT state FROM mes_wm_return_source_reservation WHERE return_line_id=101",String.class));
        equal("0","SELECT returned_quantity FROM mes_cost_return_source_balance");
        equal("2","SELECT source_quantity FROM mes_cost_return_source_balance");
        for(String table:List.of("mes_cost_return_allocation","mes_cost_layer_basis"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_cost_stock_layer",Integer.class));
        equal("-3","SELECT quantity FROM mes_wm_transaction WHERE id=23");
        equal("90","SELECT material_amount+labor_amount+overhead_amount FROM mes_cost_allocation WHERE transaction_id=23");
    }
}
