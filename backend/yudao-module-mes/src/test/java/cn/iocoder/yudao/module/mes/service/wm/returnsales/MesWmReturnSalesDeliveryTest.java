package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.MesWmReturnSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.service.md.item.*;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeService;
import cn.iocoder.yudao.module.mes.service.wm.batch.*;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Actual batch, original sales/line/detail and negative ledger evidence, with real generic validation. */
@Import({MesWmReturnSalesLineServiceImpl.class, MesWmBatchServiceImpl.class, MesWmReturnSalesDeliveryService.class,MesWmReturnSalesSourceService.class,
        MesWmReturnSalesDeliveryTest.Database.class})
class MesWmReturnSalesDeliveryTest extends BaseDbUnitTest {
    @Configuration(proxyBeanMethods=false) static class Database {
        @Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource source){return new JdbcTemplate(source);}
    }
    @Resource MesWmReturnSalesLineService lines;
    @Resource MesWmBatchService batches;
    @Resource JdbcTemplate jdbc;
    @MockitoBean MesWmReturnSalesService returns;
    @MockitoBean MesMdItemService items;
    @MockitoBean MesMdItemBatchConfigService configs;
    @MockitoBean MesMdAutoCodeRecordService codes;
    @MockitoBean MesWmBarcodeService barcodes;
    @MockitoBean MesWmReturnSalesDetailService details;

    @BeforeEach void fixture(){
        TenantContextHolder.setTenantId(0L);
        when(items.validateItemExistsAndEnable(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        when(returns.validateReturnSalesExistsAndPrepare(1L)).thenReturn(new MesWmReturnSalesDO().setId(1L).setClientId(10L).setStatus(0));
        jdbc.update("INSERT INTO mes_wm_batch(id,code,item_id,client_id) VALUES(9,'BROKY-B9',7,NULL)");
        jdbc.update("INSERT INTO mes_wm_product_sales(id,client_id,status) VALUES(20,10,4)");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,item_id,quantity,batch_id,batch_code,quality_status,oqc_check_flag) VALUES(21,20,7,5,9,'BROKY-B9',1,FALSE)");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(22,20,21,7,5,9,'BROKY-B9',2,3,4,8)");
        jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(23,2,118,20,21,7,-5,9,'BROKY-B9',2,3,4,8)");
    }
    @AfterEach void clearTenant(){TenantContextHolder.clear();}
    private MesWmReturnSalesLineSaveReqVO request(){var r=new MesWmReturnSalesLineSaveReqVO();r.setReturnId(1L);r.setOriginTransactionId(23L);r.setItemId(7L);r.setBatchId(9L);r.setBatchCode("BROKY-B9");r.setQuantity(BigDecimal.ONE);r.setRqcCheckFlag(false);return r;}
    @Test void neutralBrokyBatchActuallyShippedToClientCanBeReturned(){assertDoesNotThrow(()->lines.createReturnSalesLine(request()));}
    @Test void customerMetadataAloneCannotProveActualDelivery(){jdbc.update("UPDATE mes_wm_batch SET client_id=10 WHERE id=9");jdbc.update("DELETE FROM mes_wm_transaction");assertThrows(ServiceException.class,()->lines.createReturnSalesLine(request()));}
    @Test void genericCustomerAndVendorChecksRemainStrict(){assertThrows(ServiceException.class,()->batches.validateBatchExists(9L,7L,10L,null));assertThrows(ServiceException.class,()->batches.validateBatchExists(9L,7L,null,99L));}
    @Test void actualDeliveryTakesPrecedenceOverBatchCustomerNamingMetadata(){jdbc.update("UPDATE mes_wm_batch SET client_id=99 WHERE id=9");assertDoesNotThrow(()->lines.createReturnSalesLine(request()));}
    @Test void unspecifiedSalesLineBatchUsesActualPickedBatch(){jdbc.update("UPDATE mes_wm_product_sales_line SET batch_id=NULL,batch_code=NULL");assertDoesNotThrow(()->lines.createReturnSalesLine(request()));}
    @Test void splitLedgerAndMultipleDetailsAreAggregatedWithoutJoinMultiplication(){
        jdbc.update("UPDATE mes_wm_transaction SET quantity=-2 WHERE id=23");
        jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(24,2,118,20,21,7,-3,9,'BROKY-B9',2,3,4,8)");
        jdbc.update("UPDATE mes_wm_product_sales_detail SET quantity=1 WHERE id=22");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(25,20,21,7,4,9,'BROKY-B9',2,3,4,8)");
        assertDoesNotThrow(()->lines.createReturnSalesLine(request()));
    }
    @ParameterizedTest @ValueSource(strings={
        "UPDATE mes_wm_product_sales SET client_id=11", "UPDATE mes_wm_product_sales SET status=5", "UPDATE mes_wm_product_sales SET deleted=1",
        "UPDATE mes_wm_product_sales_line SET sales_id=999", "UPDATE mes_wm_product_sales_line SET item_id=8", "UPDATE mes_wm_product_sales_line SET quantity=6", "UPDATE mes_wm_product_sales_line SET quality_status=2", "UPDATE mes_wm_product_sales_line SET tenant_id=1", "UPDATE mes_wm_product_sales_line SET deleted=1",
        "UPDATE mes_wm_transaction SET biz_id=999", "UPDATE mes_wm_transaction SET biz_line_id=999", "UPDATE mes_wm_transaction SET item_id=8", "UPDATE mes_wm_transaction SET batch_id=99", "UPDATE mes_wm_transaction SET batch_code='OTHER'", "UPDATE mes_wm_transaction SET type=1", "UPDATE mes_wm_transaction SET quantity=5", "UPDATE mes_wm_transaction SET quantity=0", "UPDATE mes_wm_transaction SET quantity=-4", "UPDATE mes_wm_transaction SET tenant_id=1", "UPDATE mes_wm_transaction SET deleted=1",
        "UPDATE mes_wm_product_sales_detail SET sales_id=999", "UPDATE mes_wm_product_sales_detail SET line_id=999", "UPDATE mes_wm_product_sales_detail SET item_id=8", "UPDATE mes_wm_product_sales_detail SET batch_id=99", "UPDATE mes_wm_product_sales_detail SET material_stock_id=99", "UPDATE mes_wm_product_sales_detail SET warehouse_id=99", "UPDATE mes_wm_product_sales_detail SET quantity=6", "UPDATE mes_wm_product_sales_detail SET tenant_id=1", "UPDATE mes_wm_product_sales_detail SET deleted=1", "UPDATE mes_wm_batch SET tenant_id=1"
    }) void malformedOrForeignEvidenceIsRejected(String sql){jdbc.update(sql);assertThrows(ServiceException.class,()->lines.createReturnSalesLine(request()));}
    @Test void noBatchItemCanReturnOnlyAnActualNullBatchDelivery(){
        when(items.validateItemExistsAndEnable(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(false));
        for(String table:java.util.List.of("mes_wm_product_sales_line","mes_wm_product_sales_detail","mes_wm_transaction"))jdbc.update("UPDATE "+table+" SET batch_id=NULL,batch_code=NULL");
        var req=request();req.setBatchId(null);req.setBatchCode(null);assertDoesNotThrow(()->lines.createReturnSalesLine(req));
    }
    @Test void exactOriginRetainsActualBatchEvenWhenItemNoLongerRequiresBatch(){when(items.validateItemExistsAndEnable(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(false));var req=request();req.setBatchId(null);req.setBatchCode(null);Long created=assertDoesNotThrow(()->lines.createReturnSalesLine(req));assertEquals(9L,lines.getReturnSalesLine(created).getBatchId());}
    @Test void conflictingBatchCodeIsRejected(){var req=request();req.setBatchCode("FORGED");assertThrows(ServiceException.class,()->lines.createReturnSalesLine(req));}
    @Test void exactOriginDerivesRequiredBatchWhenLegacyInputsAreOmitted(){var req=request();req.setBatchId(null);req.setBatchCode(null);Long created=assertDoesNotThrow(()->lines.createReturnSalesLine(req));assertEquals(9L,lines.getReturnSalesLine(created).getBatchId());}
}
