package cn.iocoder.yudao.module.mes.service.qc.oqc;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo.MesQcOqcSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.MesQcTemplateItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.*;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo.MesQcOqcRespVO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.*;
import cn.iocoder.yudao.module.mes.service.md.client.MesMdClientService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.defectrecord.MesQcDefectRecordService;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.mes.service.qc.template.MesQcTemplateItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.productsales.*;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual QC and sales callbacks, real H2/MyBatis rows and transaction rollback. */
@Import({MesQcOqcServiceImpl.class,MesWmProductSalesServiceImpl.class,MesWmProductSalesLineServiceImpl.class,
        MesWmSalesNoticeFulfillmentService.class,MesQcOqcSourceIntegrityTest.Transactions.class})
class MesQcOqcSourceIntegrityTest extends BaseDbUnitTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions { @Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource ds){return new JdbcTemplate(ds);} }
    @Resource MesQcOqcService oqc;
    @Resource MesQcOqcMapper oqcMapper;
    @Resource MesWmProductSalesMapper salesMapper;
    @Resource MesWmProductSalesLineMapper lineMapper;
    @Resource JdbcTemplate jdbc;
    @MockitoBean MesMdClientService clients;
    @MockitoBean MesMdItemService items;
    @MockitoBean MesQcTemplateItemService templates;
    @MockitoBean MesQcOqcLineService qcLines;
    @MockitoBean MesQcDefectRecordService defects;
    @MockitoBean MesQcIndicatorResultService results;
    @MockitoBean AdminUserApi users;
    @MockitoBean MesWmProductSalesDetailService salesDetails;
    @MockitoBean MesWmSalesNoticeService notices;
    @MockitoBean MesWmBatchService batches;
    @MockitoBean MesWmWarehouseAreaService areas;
    @MockitoBean MesWmTransactionService transactions;
    MesWmProductSalesDO sales;
    MesWmProductSalesLineDO line;
    @BeforeEach void fixture() {
        TenantContextHolder.setTenantId(0L);
        when(templates.getRequiredTemplateByItemIdAndType(anyLong(),anyInt())).thenReturn(new MesQcTemplateItemDO().setTemplateId(12L));
        sales=new MesWmProductSalesDO().setCode(UUID.randomUUID().toString()).setName("real shipment").setClientId(1L).setStatus(1);salesMapper.insert(sales);
        line=new MesWmProductSalesLineDO().setSalesId(sales.getId()).setItemId(7L).setQuantity(BigDecimal.TEN).setOqcCheckFlag(true).setQualityStatus(0);lineMapper.insert(line);
    }
    @AfterEach void clearTenant(){TenantContextHolder.clear();}
    MesQcOqcSaveReqVO request() {
        var r=new MesQcOqcSaveReqVO();r.setCode(UUID.randomUUID().toString());r.setName("outgoing inspection");r.setSourceDocType(118);r.setSourceDocId(sales.getId());r.setSourceLineId(line.getId());r.setItemId(7L);r.setClientId(1L);
        r.setOutQuantity(BigDecimal.TEN);r.setCheckQuantity(BigDecimal.ONE);r.setQualifiedQuantity(BigDecimal.ONE);r.setUnqualifiedQuantity(BigDecimal.ZERO);r.setCheckResult(1);r.setOutDate(LocalDateTime.now());r.setInspectDate(LocalDateTime.now());r.setInspectorUserId(1L);return r;
    }
    @Test void anotherItemsPassCannotBeCreatedForThisSalesLine() {
        var r=request();r.setItemId(8L);assertThrows(ServiceException.class,()->oqc.createOqc(r));assertTrue(oqcMapper.selectList().isEmpty());
    }
    @Test void sourceDocumentCannotBeForgedWhileKeepingARealLineId() {
        var other=new MesWmProductSalesDO().setCode(UUID.randomUUID().toString()).setClientId(1L).setStatus(1);salesMapper.insert(other);
        var r=request();r.setSourceDocId(other.getId());assertThrows(ServiceException.class,()->oqc.createOqc(r));assertTrue(oqcMapper.selectList().isEmpty());
    }
    @Test void anotherClientsInspectionCannotBeCreatedForThisShipment() {
        var r=request();r.setClientId(2L);assertThrows(ServiceException.class,()->oqc.createOqc(r));assertTrue(oqcMapper.selectList().isEmpty());
    }
    MesQcOqcDO legacy(MesQcOqcSaveReqVO request) {
        var row=BeanUtils.toBean(request,MesQcOqcDO.class).setTemplateId(12L).setStatus(0);oqcMapper.insert(row);return row;
    }
    void pending(){assertEquals(1,salesMapper.selectById(sales.getId()).getStatus());assertEquals(0,lineMapper.selectById(line.getId()).getQualityStatus());assertNull(lineMapper.selectById(line.getId()).getOqcId());}
    @Test void genuineSourceInspectionFinishesAndReleasesOnlyItsRealSalesLine() {
        Long id=oqc.createOqc(request());assertEquals(sales.getCode(),oqcMapper.selectById(id).getSourceDocCode());
        jdbc.update("UPDATE mes_qc_oqc SET source_doc_code='FORGED-CACHE' WHERE id=?",id);oqc.finishOqc(id);
        assertEquals(4,oqcMapper.selectById(id).getStatus());assertEquals(sales.getCode(),oqcMapper.selectById(id).getSourceDocCode());
        assertEquals(1,lineMapper.selectById(line.getId()).getQualityStatus());assertEquals(id,lineMapper.selectById(line.getId()).getOqcId());assertEquals(2,salesMapper.selectById(sales.getId()).getStatus());
        assertThrows(ServiceException.class,()->oqc.finishOqc(id));assertEquals(id,lineMapper.selectById(line.getId()).getOqcId());
    }
    @Test void legacyWrongSourcesNeverReachResultValidationOrSalesCallback() {
        for(String fault:new String[]{"item","client","parent","line","batch","quantity"}) {
            var r=request();switch(fault){case "item"->r.setItemId(8L);case "client"->r.setClientId(2L);case "parent"->r.setSourceDocId(999999L);case "line"->r.setSourceLineId(999999L);case "batch"->r.setBatchCode("OTHER");case "quantity"->r.setOutQuantity(BigDecimal.ONE);}
            var row=legacy(r);assertThrows(ServiceException.class,()->oqc.finishOqc(row.getId()),fault);assertEquals(0,oqcMapper.selectById(row.getId()).getStatus());pending();
        }
        verifyNoInteractions(results);
    }
    @Test void sourceIdentityAndItsTemplateItemCannotBeChangedOnUpdate() {
        Long id=oqc.createOqc(request());
        for(String fault:new String[]{"type","parent","line","item","client"}) {
            var r=request();r.setId(id);r.setCode(oqcMapper.selectById(id).getCode());
            switch(fault){case "type"->r.setSourceDocType(null);case "parent"->r.setSourceDocId(999999L);case "line"->r.setSourceLineId(999999L);case "item"->r.setItemId(8L);case "client"->r.setClientId(2L);}
            assertThrows(ServiceException.class,()->oqc.updateOqc(r),fault);
        }
        assertEquals(7L,oqcMapper.selectById(id).getItemId());assertEquals(1L,oqcMapper.selectById(id).getClientId());pending();
    }
    @Test void legitimateUpdateRechecksActualSourceAndKeepsCanonicalCode() {
        Long id=oqc.createOqc(request());var r=request();r.setId(id);r.setCode(oqcMapper.selectById(id).getCode());r.setRemark("updated findings");oqc.updateOqc(r);
        assertEquals("updated findings",oqcMapper.selectById(id).getRemark());assertEquals(sales.getCode(),oqcMapper.selectById(id).getSourceDocCode());
        jdbc.update("UPDATE mes_wm_product_sales_line SET item_id=8 WHERE id=?",line.getId());assertThrows(ServiceException.class,()->oqc.updateOqc(r));assertEquals(0,oqcMapper.selectById(id).getStatus());
    }
    @Test void sourceMustStillBeWaitingForInspectionAtCreateAndFinish() {
        Long existing=oqc.createOqc(request());
        for(String fault:new String[]{"closed","not-required","already-passed","already-linked"}) {
            jdbc.update("UPDATE mes_wm_product_sales SET status=1 WHERE id=?",sales.getId());jdbc.update("UPDATE mes_wm_product_sales_line SET oqc_check_flag=TRUE,quality_status=0,oqc_id=NULL WHERE id=?",line.getId());
            switch(fault){case "closed"->jdbc.update("UPDATE mes_wm_product_sales SET status=5 WHERE id=?",sales.getId());case "not-required"->jdbc.update("UPDATE mes_wm_product_sales_line SET oqc_check_flag=FALSE WHERE id=?",line.getId());case "already-passed"->jdbc.update("UPDATE mes_wm_product_sales_line SET quality_status=1 WHERE id=?",line.getId());case "already-linked"->jdbc.update("UPDATE mes_wm_product_sales_line SET oqc_id=999 WHERE id=?",line.getId());}
            assertThrows(ServiceException.class,()->oqc.createOqc(request()),fault);assertThrows(ServiceException.class,()->oqc.finishOqc(existing),fault);assertEquals(0,oqcMapper.selectById(existing).getStatus());
            assertThrows(ServiceException.class,()->oqc.getSourceContext(sales.getId(),line.getId()),fault);
        }
        verifyNoInteractions(results);
    }
    @Test void manualInspectionsRemainSupportedButPartialAndUnknownSourcesFailClosed() {
        for(Integer type:new Integer[]{null,0}) {
            var r=request();r.setSourceDocType(type);r.setSourceDocId(null);r.setSourceLineId(null);Long id=oqc.createOqc(r);oqc.finishOqc(id);assertEquals(4,oqcMapper.selectById(id).getStatus());pending();
        }
        for(String fault:new String[]{"null-type","zero-type","missing-parent","missing-line","unknown"}) {
            var r=request();switch(fault){case "null-type"->r.setSourceDocType(null);case "zero-type"->r.setSourceDocType(0);case "missing-parent"->r.setSourceDocId(null);case "missing-line"->r.setSourceLineId(null);case "unknown"->r.setSourceDocType(999);}
            assertThrows(ServiceException.class,()->oqc.createOqc(r),fault);
        }
    }
    @Test void malformedOrExpiredDraftsCanBeDeletedWithoutTouchingSales() {
        var wrong=request();wrong.setSourceDocId(999999L);wrong.setSourceLineId(null);var malformed=legacy(wrong);oqc.deleteOqc(malformed.getId());assertNull(oqcMapper.selectById(malformed.getId()));pending();
        Long valid=oqc.createOqc(request());jdbc.update("UPDATE mes_wm_product_sales SET status=5 WHERE id=?",sales.getId());oqc.deleteOqc(valid);assertNull(oqcMapper.selectById(valid));assertEquals(5,salesMapper.selectById(sales.getId()).getStatus());assertNull(lineMapper.selectById(line.getId()).getOqcId());
    }
    @Test void missingInspectionEvidenceDoesNotCommitCompletionOrSalesRelease() {
        Long id=oqc.createOqc(request());doThrow(new ServiceException(999,"missing results")).when(results).validateIndicatorResultExistsByQcIdAndType(id,3);
        assertThrows(ServiceException.class,()->oqc.finishOqc(id));assertEquals(0,oqcMapper.selectById(id).getStatus());pending();
    }
    @Test void oneMillionthIsSavedFinishedAndSerializedWithoutRounding() throws Exception {
        BigDecimal tiny=new BigDecimal("0.000001");lineMapper.updateById(new MesWmProductSalesLineDO().setId(line.getId()).setQuantity(tiny));
        var r=request();r.setOutQuantity(tiny);r.setCheckQuantity(tiny);r.setQualifiedQuantity(tiny);Long id=oqc.createOqc(r);oqc.finishOqc(id);
        var stored=oqcMapper.selectById(id);assertEquals(0,tiny.compareTo(stored.getOutQuantity()));assertEquals(0,tiny.compareTo(stored.getCheckQuantity()));assertEquals(0,tiny.compareTo(stored.getQualifiedQuantity()));assertEquals(1,lineMapper.selectById(line.getId()).getQualityStatus());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var tree=json.readTree(json.writeValueAsString(BeanUtils.toBean(stored,MesQcOqcRespVO.class)));assertTrue(tree.get("outQuantity").isTextual());assertEquals("0.000001",tree.get("outQuantity").asText());assertEquals("0.000001",tree.get("checkQuantity").asText());
    }
    @Test void requestRejectsUnsupportedFractionalPrecisionBeforeStorage() {
        var r=request();r.setOutQuantity(new BigDecimal("0.0000001"));
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){assertFalse(factory.getValidator().validate(r).isEmpty());}
    }
    @Test void sourceContextReturnsOnlyCurrentInspectionFieldsAndNeedsOnlyQcCreatePermission() throws Exception {
        BigDecimal tiny=new BigDecimal("0.000001");lineMapper.updateById(new MesWmProductSalesLineDO().setId(line.getId()).setQuantity(tiny).setBatchCode("REAL-BATCH"));
        var context=oqc.getSourceContext(sales.getId(),line.getId());assertEquals(118,context.getSourceDocType());assertEquals(sales.getId(),context.getSourceDocId());assertEquals(line.getId(),context.getSourceLineId());assertEquals(sales.getCode(),context.getSourceDocCode());assertEquals(1L,context.getClientId());assertEquals(7L,context.getItemId());assertEquals("REAL-BATCH",context.getBatchCode());assertEquals(0,tiny.compareTo(context.getOutQuantity()));
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(context));assertEquals(8,json.size());assertTrue(json.get("outQuantity").isTextual());assertEquals("0.000001",json.get("outQuantity").asText());assertTrue(oqcMapper.selectList().isEmpty());
        var permission=cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.MesQcOqcController.class.getMethod("getSourceContext",Long.class,Long.class).getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();assertEquals("@ss.hasPermission('mes:qc-oqc:create')",permission);
    }
    @Test void sourceContextRejectsForgedParentAndMissingOrMovedSourceLine() {
        assertThrows(ServiceException.class,()->oqc.getSourceContext(999999L,line.getId()));assertThrows(ServiceException.class,()->oqc.getSourceContext(sales.getId(),999999L));assertThrows(ServiceException.class,()->oqc.getSourceContext(sales.getId(),null));
        jdbc.update("UPDATE mes_wm_product_sales_line SET sales_id=999999 WHERE id=?",line.getId());assertThrows(ServiceException.class,()->oqc.getSourceContext(sales.getId(),line.getId()));assertTrue(oqcMapper.selectList().isEmpty());
    }
}
