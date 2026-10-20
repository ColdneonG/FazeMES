package cn.iocoder.yudao.module.mes.service.wm.productsales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.line.MesWmProductSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.salesnotice.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.salesnotice.*;
import cn.iocoder.yudao.module.mes.service.md.client.MesMdClientService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesShippingReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.detail.MesWmProductSalesDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.vo.line.MesWmSalesNoticeLineSaveReqVO;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import static org.junit.jupiter.api.Assertions.*;

/** Real document services, MyBatis, H2 and transaction proxies; external stock posting is isolated. */
@Import({MesWmProductSalesServiceImpl.class,MesWmProductSalesLineServiceImpl.class,
        MesWmProductSalesDetailServiceImpl.class,MesWmSalesNoticeServiceImpl.class,
        MesWmSalesNoticeLineServiceImpl.class,MesWmSalesNoticeFulfillmentService.class,
        cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.MesWmSalesNoticeLineController.class,
        cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.MesWmProductSalesLineController.class,MesWmSalesFulfillmentTest.Transactions.class})
class MesWmSalesFulfillmentTest extends BaseDbUnitTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) static class Transactions {
        @Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource){return new JdbcTemplate(dataSource);}
    }
    @Resource MesWmProductSalesService sales;
    @Resource MesWmProductSalesLineService lines;
    @Resource MesWmProductSalesMapper salesMapper;
    @Resource MesWmProductSalesLineMapper lineMapper;
    @Resource MesWmSalesNoticeMapper salesNoticeMapper;
    @Resource MesWmSalesNoticeLineMapper salesNoticeLineMapper;
    @Resource JdbcTemplate jdbc;
    @Resource MesWmSalesNoticeFulfillmentService fulfillment;
    @Resource MesWmProductSalesDetailService details;
    @Resource MesWmProductSalesDetailMapper detailMapper;
    @Resource MesWmSalesNoticeService notices;
    @Resource MesWmSalesNoticeLineService noticeLines;
    @Resource cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.MesWmSalesNoticeLineController noticeApi;
    @Resource cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.MesWmProductSalesLineController lineApi;
    @MockitoBean cn.iocoder.yudao.module.mes.service.md.unitmeasure.MesMdUnitMeasureService units;
    @MockitoBean MesMdClientService clients;
    @MockitoBean MesMdItemService items;
    @MockitoBean MesWmBatchService batches;
    @MockitoBean MesWmMaterialStockService stocks;
    @MockitoBean MesWmWarehouseAreaService areas;
    @MockitoBean MesQcOqcService oqc;
    @MockitoBean MesWmTransactionService transactions;

    @BeforeEach void setup(){
        TenantContextHolder.setTenantId(0L);
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_sales_fulfillment_test_stock(id bigint PRIMARY KEY,quantity decimal(20,6))");
        jdbc.update("DELETE FROM mes_sales_fulfillment_test_stock");jdbc.update("INSERT INTO mes_sales_fulfillment_test_stock VALUES(1,100)");
        doAnswer(call->{List<MesWmTransactionSaveReqDTO> requests=call.getArgument(0);for(var r:requests){
            if(jdbc.update("UPDATE mes_sales_fulfillment_test_stock SET quantity=quantity+? WHERE id=1 AND quantity+?>=0",r.getQuantity(),r.getQuantity())!=1)throw new ServiceException(1,"insufficient stock");
            jdbc.update("INSERT INTO mes_wm_transaction(tenant_id,biz_type,biz_id,biz_line_id,item_id,batch_id,batch_code,quantity) VALUES(0,?,?,?,?,?,?,?)",r.getBizType(),r.getBizId(),r.getBizLineId(),r.getItemId(),r.getBatchId(),r.getBatchCode(),r.getQuantity());
        }return null;}).when(transactions).createTransactionList(anyList());
    }
    @AfterEach void clearTenant(){TenantContextHolder.clear();}

    MesWmSalesNoticeLineDO notice(String quantity) {
        var n=new MesWmSalesNoticeDO().setCode(UUID.randomUUID().toString()).setName("notice").setClientId(1L).setStatus(3);salesNoticeMapper.insert(n);
        var l=new MesWmSalesNoticeLineDO().setNoticeId(n.getId()).setItemId(7L).setQuantity(new BigDecimal(quantity)).setOqcCheckFlag(false);salesNoticeLineMapper.insert(l);return l;
    }
    MesWmProductSalesDO sale(MesWmSalesNoticeLineDO n,int status) {
        var s=new MesWmProductSalesDO().setCode(UUID.randomUUID().toString()).setName("sale").setClientId(1L).setNoticeId(n.getNoticeId()).setStatus(status);salesMapper.insert(s);return s;
    }
    MesWmProductSalesLineSaveReqVO request(MesWmProductSalesDO s,MesWmSalesNoticeLineDO n,String qty) {
        var r=new MesWmProductSalesLineSaveReqVO();r.setSalesId(s.getId());r.setNoticeLineId(n.getId());r.setItemId(7L);r.setQuantity(new BigDecimal(qty));r.setOqcCheckFlag(false);return r;
    }
    @Test void twoDocumentsCannotEachReserveTheEntireSameNotice() {
        var n=notice("10");lines.createProductSalesLine(request(sale(n,0),n,"10"));
        assertThrows(ServiceException.class,()->lines.createProductSalesLine(request(sale(n,0),n,"10")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_sales_line WHERE notice_line_id=? AND deleted=0",Integer.class,n.getId()));
    }
    MesWmProductSalesLineDO saved(MesWmProductSalesDO s,MesWmSalesNoticeLineDO n,String qty){return lineMapper.selectById(lines.createProductSalesLine(request(s,n,qty)));}
    MesWmProductSalesDetailDO picked(MesWmProductSalesLineDO l){var d=new MesWmProductSalesDetailDO().setSalesId(l.getSalesId()).setLineId(l.getId()).setItemId(l.getItemId()).setQuantity(l.getQuantity()).setMaterialStockId(1L).setWarehouseId(1L).setLocationId(1L).setAreaId(1L).setBatchId(l.getBatchId()).setBatchCode(l.getBatchCode());detailMapper.insert(d);return d;}
    void finish(MesWmProductSalesDO s,MesWmProductSalesLineDO l){sales.submitProductSales(s.getId());picked(l);sales.stockProductSales(s.getId());assertEquals(10,salesMapper.selectById(s.getId()).getStatus());var ship=new MesWmProductSalesShippingReqVO();ship.setId(s.getId());ship.setCarrier("carrier");ship.setShippingNumber("tracking");sales.shippingProductSales(ship);sales.finishProductSales(s.getId());}
    void qty(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    @Test void partialFourAndSixCompleteOnlyAfterActualPosting(){
        var n=notice("10");var first=sale(n,0);var l1=saved(first,n,"4");var second=sale(n,0);var l2=saved(second,n,"6");
        var initial=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());qty("10",initial.getReservedQuantity());qty("0",initial.getShippedQuantity());assertEquals(3,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());
        finish(first,l1);var partial=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());qty("4",partial.getShippedQuantity());qty("6",partial.getReservedQuantity());assertEquals(3,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());
        finish(second,l2);assertEquals(4,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());qty("90",jdbc.queryForObject("SELECT quantity FROM mes_sales_fulfillment_test_stock WHERE id=1",BigDecimal.class));
        var end=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());assertTrue(end.isFulfillmentReliable());qty("10",end.getShippedQuantity());qty("0",end.getReservedQuantity());qty("0",end.getAvailableQuantity());
        assertThrows(ServiceException.class,()->sales.finishProductSales(second.getId()));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction",Integer.class));
    }
    @Test void excessPartialAndDuplicateLinesWithinOneDocumentAreRejected(){var n=notice("10");var s=sale(n,0);saved(s,n,"4");assertThrows(ServiceException.class,()->saved(s,n,"7"));saved(s,n,"6");assertThrows(ServiceException.class,()->saved(sale(n,0),n,"0.000001"));}
    @Test void editAddsBackOnlyOwnReservationAndCancelReleasesIt(){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"4");var sum=fulfillment.summaries(n.getNoticeId(),l.getId()).get(n.getId());qty("6",sum.getAvailableQuantity());qty("10",sum.getEditableAvailableQuantity());var r=request(s,n,"5");r.setId(l.getId());lines.updateProductSalesLine(r);qty("5",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getReservedQuantity());sales.cancelProductSales(s.getId());qty("10",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getAvailableQuantity());saved(sale(n,0),n,"10");}
    @Test void draftDeleteAndLineDeleteReleaseQuotaAndCascadePicking(){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"6");var d=picked(l);lines.deleteProductSalesLine(l.getId());assertNull(detailMapper.selectById(d.getId()));qty("10",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getAvailableQuantity());l=saved(s,n,"10");d=picked(l);sales.deleteProductSales(s.getId());assertNull(detailMapper.selectById(d.getId()));qty("10",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getAvailableQuantity());}
    @Test void failedPostingRollsBackStockLedgerAndBothStatuses(){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"10");sales.submitProductSales(s.getId());picked(l);sales.stockProductSales(s.getId());var shipping=new MesWmProductSalesShippingReqVO();shipping.setId(s.getId());shipping.setCarrier("c");shipping.setShippingNumber("n");sales.shippingProductSales(shipping);doAnswer(call->{jdbc.update("UPDATE mes_sales_fulfillment_test_stock SET quantity=90 WHERE id=1");jdbc.update("INSERT INTO mes_wm_transaction(tenant_id,biz_type,biz_id,biz_line_id,item_id,quantity) VALUES(0,118,?,?,7,-10)",s.getId(),l.getId());throw new IllegalStateException("cost layer failure");}).when(transactions).createTransactionList(anyList());assertThrows(IllegalStateException.class,()->sales.finishProductSales(s.getId()));qty("100",jdbc.queryForObject("SELECT quantity FROM mes_sales_fulfillment_test_stock",BigDecimal.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction",Integer.class));assertEquals(3,salesMapper.selectById(s.getId()).getStatus());assertEquals(3,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());}
    @Test void headerCannotMoveNoticeOrCustomerAfterLineExists(){var n=notice("10");var other=notice("10");var s=sale(n,0);saved(s,n,"4");var r=BeanUtils.toBean(s,MesWmProductSalesSaveReqVO.class);r.setSalesDate(java.time.LocalDateTime.now());r.setNoticeId(other.getNoticeId());assertThrows(ServiceException.class,()->sales.updateProductSales(r));r.setNoticeId(n.getNoticeId());r.setClientId(2L);assertThrows(ServiceException.class,()->sales.updateProductSales(r));assertEquals(n.getNoticeId(),salesMapper.selectById(s.getId()).getNoticeId());}
    @Test void specifiedBatchCannotBeReplacedByPickingAnotherBatch(){var n=notice("10");n.setBatchCode("A");n.setBatchId(9L);salesNoticeLineMapper.updateById(n);when(batches.getBatchByCode("A")).thenReturn(new cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO().setId(9L).setItemId(7L).setCode("A"));var s=sale(n,0);var r=request(s,n,"4");r.setBatchCode("A");var l=lineMapper.selectById(lines.createProductSalesLine(r));var d=BeanUtils.toBean(picked(l),MesWmProductSalesDetailSaveReqVO.class);d.setId(null);d.setBatchCode("B");d.setBatchId(10L);assertThrows(ServiceException.class,()->details.createProductSalesDetail(d));sales.submitProductSales(s.getId());jdbc.update("UPDATE mes_wm_product_sales_detail SET batch_id=10,batch_code='B' WHERE line_id=?",l.getId());assertThrows(ServiceException.class,()->sales.stockProductSales(s.getId()));salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(3));assertThrows(ServiceException.class,()->sales.finishProductSales(s.getId()));verify(transactions,never()).createTransactionList(anyList());}
    @Test void forgedForeignNoticeLineAndUnlinkedNoticeLineAreRejected(){var n=notice("10");var other=notice("10");var s=sale(n,0);assertThrows(ServiceException.class,()->saved(s,other,"4"));s.setNoticeId(null);jdbc.update("UPDATE mes_wm_product_sales SET notice_id=NULL WHERE id=?",s.getId());assertThrows(ServiceException.class,()->saved(s,n,"4"));}
    @Test void oneOfTwoNoticeLinesShippedDoesNotCompleteNotice(){var n=notice("4");var second=new MesWmSalesNoticeLineDO().setNoticeId(n.getNoticeId()).setItemId(7L).setQuantity(new BigDecimal("6")).setOqcCheckFlag(false);salesNoticeLineMapper.insert(second);var s=sale(n,0);var l=saved(s,n,"4");finish(s,l);assertEquals(3,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());}
    @Test void failedOqcCancelsAndReleasesReservation(){var n=notice("10");salesNoticeLineMapper.updateById(new MesWmSalesNoticeLineDO().setId(n.getId()).setOqcCheckFlag(true));var s=sale(n,0);var r=request(s,n,"10");r.setOqcCheckFlag(true);var lineId=lines.createProductSalesLine(r);sales.submitProductSales(s.getId());lines.updateProductSalesLineWhenOqcFinish(lineId,12L,2);assertEquals(5,salesMapper.selectById(s.getId()).getStatus());qty("10",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getAvailableQuantity());assertThrows(ServiceException.class,()->lines.updateProductSalesLineWhenOqcFinish(lineId,13L,1));}
    @Test void allOqcPassAdvancesButDuplicateCallbackCannotRewriteResult(){var n=notice("10");salesNoticeLineMapper.updateById(new MesWmSalesNoticeLineDO().setId(n.getId()).setOqcCheckFlag(true));var s=sale(n,0);var a=request(s,n,"4");a.setOqcCheckFlag(true);var first=lines.createProductSalesLine(a);var b=request(s,n,"6");b.setOqcCheckFlag(true);var second=lines.createProductSalesLine(b);sales.submitProductSales(s.getId());lines.updateProductSalesLineWhenOqcFinish(first,12L,1);assertEquals(1,salesMapper.selectById(s.getId()).getStatus());lines.updateProductSalesLineWhenOqcFinish(second,13L,1);assertEquals(2,salesMapper.selectById(s.getId()).getStatus());assertThrows(ServiceException.class,()->lines.updateProductSalesLineWhenOqcFinish(first,99L,2));assertEquals(1,lineMapper.selectById(first).getQualityStatus());}
    @Test void forgedHeaderApprovalCannotBypassPendingOrFailedOqc(){for(int quality:new int[]{0,2}){var n=notice("10");salesNoticeLineMapper.updateById(new MesWmSalesNoticeLineDO().setId(n.getId()).setOqcCheckFlag(true));var s=sale(n,0);var r=request(s,n,"10");r.setOqcCheckFlag(true);var id=lines.createProductSalesLine(r);lineMapper.updateById(new MesWmProductSalesLineDO().setId(id).setQualityStatus(quality).setOqcId(99L));picked(lineMapper.selectById(id));salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(1));assertThrows(ServiceException.class,()->sales.confirmProductSales(s.getId()));salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(3));assertThrows(ServiceException.class,()->sales.finishProductSales(s.getId()));}verify(transactions,never()).createTransactionList(anyList());}
    @Test void quantitiesKeepSixDecimalPrecisionAndRejectInvalidInput(){var n=notice("0.000003");var s=sale(n,0);saved(s,n,"0.000001");saved(s,n,"0.000002");for(String q:new String[]{"0","-1","0.0000001","100000000000000"})assertThrows(ServiceException.class,()->saved(s,n,q));qty("0.000003",fulfillment.summaries(n.getNoticeId(),null).get(n.getId()).getReservedQuantity());}
    @Test void noticeDraftSupportsOneMillionthAndCannotMutateAfterSubmission(){var n=notice("1");jdbc.update("UPDATE mes_wm_sales_notice SET status=0 WHERE id=?",n.getNoticeId());var r=BeanUtils.toBean(n,MesWmSalesNoticeLineSaveReqVO.class);r.setQuantity(new BigDecimal("0.000001"));noticeLines.updateSalesNoticeLine(r);notices.submitSalesNotice(n.getNoticeId());qty("0.000001",salesNoticeLineMapper.selectById(n.getId()).getQuantity());assertThrows(ServiceException.class,()->noticeLines.updateSalesNoticeLine(r));}
    void posting(MesWmProductSalesDO s,MesWmProductSalesLineDO l,String qty){jdbc.update("INSERT INTO mes_wm_transaction(tenant_id,biz_type,biz_id,biz_line_id,item_id,quantity) VALUES(0,118,?,?,7,?)",s.getId(),l.getId(),new BigDecimal(qty).negate());}
    @Test void unreliableLegacyPostingNeverReleasesQuota(){for(String fault:new String[]{"missing","extra","deleted-line","deleted-header","deleted-ledger","active-ledger","wrong-parent","positive"}){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"10");salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(4));if(!fault.equals("missing"))posting(s,l,fault.equals("extra")?"11":"10");switch(fault){case "deleted-line"->jdbc.update("UPDATE mes_wm_product_sales_line SET deleted=TRUE WHERE id=?",l.getId());case "deleted-header"->jdbc.update("UPDATE mes_wm_product_sales SET deleted=TRUE WHERE id=?",s.getId());case "deleted-ledger"->jdbc.update("UPDATE mes_wm_transaction SET deleted=TRUE WHERE biz_id=?",s.getId());case "active-ledger"->jdbc.update("UPDATE mes_wm_product_sales SET status=0 WHERE id=?",s.getId());case "wrong-parent"->jdbc.update("UPDATE mes_wm_transaction SET biz_line_id=999999 WHERE biz_id=?",s.getId());case "positive"->jdbc.update("UPDATE mes_wm_transaction SET quantity=10 WHERE biz_id=?",s.getId());}var sum=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());assertFalse(sum.isFulfillmentReliable(),fault);assertNotNull(sum.getFulfillmentWarning());qty("0",sum.getAvailableQuantity());qty("0",sum.getEditableAvailableQuantity());assertThrows(ServiceException.class,()->saved(sale(n,0),n,"1"));}}
    @Test void returnReceiptDoesNotReopenSalesNoticeQuota(){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"10");finish(s,l);jdbc.update("INSERT INTO mes_wm_transaction(tenant_id,biz_type,biz_id,biz_line_id,item_id,quantity) VALUES(0,119,?,?,7,10)",s.getId(),l.getId());var sum=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());assertTrue(sum.isFulfillmentReliable());qty("10",sum.getShippedQuantity());qty("0",sum.getAvailableQuantity());}
    @Test void tenantEvidenceIsIsolatedAndMissingTenantIsRejected(){var n=notice("10");jdbc.update("INSERT INTO mes_wm_product_sales(tenant_id,notice_id,status,client_id) VALUES(2,?,4,1)",n.getNoticeId());var sum=fulfillment.summaries(n.getNoticeId(),null).get(n.getId());assertTrue(sum.isFulfillmentReliable());qty("10",sum.getAvailableQuantity());TenantContextHolder.clear();assertThrows(NullPointerException.class,()->fulfillment.summaries(n.getNoticeId(),null));}
    @Test void completedSalesLineCanBeViewedWithoutAnEditingContext(){var n=notice("10");var s=sale(n,0);var l=saved(s,n,"4");finish(s,l);var response=lineApi.getProductSalesLine(l.getId()).getData();assertTrue(response.isFulfillmentReliable());qty("6",response.getAvailableQuantity());qty("6",response.getEditableAvailableQuantity());var page=new cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.line.MesWmProductSalesLinePageReqVO();page.setSalesId(s.getId());assertEquals(1,lineApi.getProductSalesLinePage(page).getData().getList().size());}
    @Test void responseQuantitiesAreDecimalStringsAndEditingQuotaIsServerCalculated() throws Exception {var n=notice("99999999999999.999999");var s=sale(n,0);var l=saved(s,n,"0.000001");var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var json=mapper.readTree(mapper.writeValueAsString(noticeApi.getSalesNoticeLine(n.getId(),l.getId()).getData()));assertTrue(json.get("quantity").isTextual());assertEquals("99999999999999.999999",json.get("noticeQuantity").asText());assertEquals("99999999999999.999999",json.get("editableAvailableQuantity").asText());assertTrue(json.get("reservedQuantity").isTextual());var saleJson=mapper.readTree(mapper.writeValueAsString(lineApi.getProductSalesLine(l.getId()).getData()));assertTrue(saleJson.get("quantity").isTextual());assertTrue(saleJson.get("editableAvailableQuantity").isTextual());assertEquals("0.000001",saleJson.get("quantity").asText());}
    @Test void emptyDraftCanClearItsNoticeAndBatchConstraintsCanBeClearedExplicitly(){var n=notice("10");var s=sale(n,0);var r=BeanUtils.toBean(s,MesWmProductSalesSaveReqVO.class);r.setSalesDate(java.time.LocalDateTime.now());r.setNoticeId(null);sales.updateProductSales(r);assertNull(salesMapper.selectById(s.getId()).getNoticeId());jdbc.update("UPDATE mes_wm_sales_notice SET status=0 WHERE id=?",n.getNoticeId());jdbc.update("UPDATE mes_wm_sales_notice_line SET batch_id=9,batch_code='A' WHERE id=?",n.getId());var lineRequest=BeanUtils.toBean(n,MesWmSalesNoticeLineSaveReqVO.class);lineRequest.setBatchCode("");noticeLines.updateSalesNoticeLine(lineRequest);assertNull(salesNoticeLineMapper.selectById(n.getId()).getBatchId());}
    void completedInspection(MesWmProductSalesDO s,MesWmProductSalesLineDO l,long inspectionId){jdbc.update("INSERT INTO mes_qc_oqc(id,code,name,template_id,source_doc_type,source_doc_id,source_line_id,client_id,item_id,out_quantity,check_quantity,qualified_quantity,unqualified_quantity,check_result,status,tenant_id) VALUES(?,?,'inspection',1,118,?,?,1,7,10,1,1,0,1,4,0)",inspectionId,UUID.randomUUID().toString(),s.getId(),l.getId());}
    @Test void legacyPassFlagCannotSubstituteForTheActualCompletedOqcEvidence(){for(String fault:new String[]{"missing","item","client","doc","line","type","batch","quantity","result","status","deleted"}){var n=notice("10");salesNoticeLineMapper.updateById(new MesWmSalesNoticeLineDO().setId(n.getId()).setOqcCheckFlag(true));var s=sale(n,0);var req=request(s,n,"10");req.setOqcCheckFlag(true);var l=lineMapper.selectById(lines.createProductSalesLine(req));long qid=900000+l.getId();lineMapper.updateById(new MesWmProductSalesLineDO().setId(l.getId()).setOqcId(qid).setQualityStatus(1));picked(l);salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(3));if(!fault.equals("missing"))completedInspection(s,l,qid);switch(fault){case "item"->jdbc.update("UPDATE mes_qc_oqc SET item_id=8 WHERE id=?",qid);case "client"->jdbc.update("UPDATE mes_qc_oqc SET client_id=2 WHERE id=?",qid);case "doc"->jdbc.update("UPDATE mes_qc_oqc SET source_doc_id=999999 WHERE id=?",qid);case "line"->jdbc.update("UPDATE mes_qc_oqc SET source_line_id=999999 WHERE id=?",qid);case "type"->jdbc.update("UPDATE mes_qc_oqc SET source_doc_type=119 WHERE id=?",qid);case "batch"->jdbc.update("UPDATE mes_qc_oqc SET batch_code='A' WHERE id=?",qid);case "quantity"->jdbc.update("UPDATE mes_qc_oqc SET out_quantity=9 WHERE id=?",qid);case "result"->jdbc.update("UPDATE mes_qc_oqc SET check_result=2 WHERE id=?",qid);case "status"->jdbc.update("UPDATE mes_qc_oqc SET status=0 WHERE id=?",qid);case "deleted"->jdbc.update("UPDATE mes_qc_oqc SET deleted=TRUE WHERE id=?",qid);}assertThrows(ServiceException.class,()->sales.finishProductSales(s.getId()),fault);assertEquals(3,salesMapper.selectById(s.getId()).getStatus());}verify(transactions,never()).createTransactionList(anyList());}
    @Test void validCompletedOqcEvidenceAllowsExactlyTheVerifiedShipment(){var n=notice("10");salesNoticeLineMapper.updateById(new MesWmSalesNoticeLineDO().setId(n.getId()).setOqcCheckFlag(true));var s=sale(n,0);var req=request(s,n,"10");req.setOqcCheckFlag(true);var l=lineMapper.selectById(lines.createProductSalesLine(req));long qid=900000+l.getId();completedInspection(s,l,qid);lineMapper.updateById(new MesWmProductSalesLineDO().setId(l.getId()).setOqcId(qid).setQualityStatus(1));picked(l);salesMapper.updateById(new MesWmProductSalesDO().setId(s.getId()).setStatus(3));sales.finishProductSales(s.getId());assertEquals(4,salesMapper.selectById(s.getId()).getStatus());assertEquals(4,salesNoticeMapper.selectById(n.getNoticeId()).getStatus());qty("90",jdbc.queryForObject("SELECT quantity FROM mes_sales_fulfillment_test_stock",BigDecimal.class));}
}
