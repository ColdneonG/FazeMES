package cn.iocoder.yudao.module.mes.service.wm.productissue;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.detail.MesWmProductIssueDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.line.MesWmProductIssueLineSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.MesWmProductIssueSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productissue.*;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.*;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.ArgumentCaptor;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2, MyBatis mappers and transaction proxies; external stock posting is isolated. */
@Import({MesWmProductIssueServiceImpl.class,MesWmProductIssueLineServiceImpl.class,MesWmProductIssueDetailServiceImpl.class,MesWmProductIssueOwnershipTest.Transactions.class})
class MesWmProductIssueOwnershipTest extends BaseDbUnitTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) static class Transactions {}
    @Resource MesWmProductIssueService issues;
    @Resource MesWmProductIssueLineService lines;
    @Resource MesWmProductIssueDetailService details;
    @Resource MesWmProductIssueMapper issueMapper;
    @Resource MesWmProductIssueLineMapper lineMapper;
    @Resource MesWmProductIssueDetailMapper detailMapper;
    @Resource PlatformTransactionManager transactionManager;
    @MockitoBean MesMdItemService itemService;
    @MockitoBean MesMdWorkstationService workstationService;
    @MockitoBean MesProWorkOrderService workOrderService;
    @MockitoBean MesProWorkOrderBomService workOrderBomService;
    @MockitoBean MesProKittingService kittingService;
    @MockitoBean MesWmWarehouseService warehouseService;
    @MockitoBean MesWmWarehouseLocationService locationService;
    @MockitoBean MesWmWarehouseAreaService areaService;
    @MockitoBean MesWmMaterialStockService stockService;
    @MockitoBean MesWmTransactionService transactionService;

    @BeforeEach void setupExternalServices() {
        when(workOrderService.validateWorkOrderExistsForUpdate(100L)).thenReturn(new MesProWorkOrderDO().setId(100L).setStatus(1));
        when(warehouseService.getWarehouseByCode(anyString())).thenReturn(new MesWmWarehouseDO().setId(101L));
        when(locationService.getWarehouseLocationByCode(anyString())).thenReturn(new MesWmWarehouseLocationDO().setId(102L));
        when(areaService.getWarehouseAreaByCode(anyString())).thenReturn(new MesWmWarehouseAreaDO().setId(103L));
        when(transactionService.createTransaction(any())).thenReturn(900L);
    }

    MesWmProductIssueDO issue(int status){var row=new MesWmProductIssueDO().setCode(UUID.randomUUID().toString()).setName("issue").setWorkOrderId(100L).setStatus(status);issueMapper.insert(row);return row;}
    MesWmProductIssueLineDO line(MesWmProductIssueDO issue){var row=new MesWmProductIssueLineDO().setIssueId(issue.getId()).setItemId(7L).setQuantity(BigDecimal.TEN);lineMapper.insert(row);return row;}
    MesWmProductIssueDetailDO detail(MesWmProductIssueLineDO line){var row=new MesWmProductIssueDetailDO().setIssueId(line.getIssueId()).setLineId(line.getId()).setItemId(line.getItemId()).setQuantity(BigDecimal.TEN).setMaterialStockId(9L).setWarehouseId(1L).setLocationId(2L).setAreaId(3L);detailMapper.insert(row);return row;}
    @Test void completedDetailCannotBeMovedIntoAnotherEditableDocument() {
        var oldIssue=issue(4);var oldLine=line(oldIssue);var oldDetail=detail(oldLine);var target=issue(2);var targetLine=line(target);
        var request=BeanUtils.toBean(oldDetail,MesWmProductIssueDetailSaveReqVO.class);request.setIssueId(target.getId());request.setLineId(targetLine.getId());
        assertThrows(ServiceException.class,()->details.updateProductIssueDetail(request));
        assertEquals(oldIssue.getId(),detailMapper.selectById(oldDetail.getId()).getIssueId());assertEquals(oldLine.getId(),detailMapper.selectById(oldDetail.getId()).getLineId());
    }
    @Test void completedLineCannotBeMovedIntoAnotherDraftDocument() {
        var oldIssue=issue(4);var oldLine=line(oldIssue);var target=issue(0);
        var request=BeanUtils.toBean(oldLine,MesWmProductIssueLineSaveReqVO.class);request.setIssueId(target.getId());
        assertThrows(ServiceException.class,()->lines.updateProductIssueLine(request));assertEquals(oldIssue.getId(),lineMapper.selectById(oldLine.getId()).getIssueId());
    }
    @Test void completedHistoryCannotBeErasedThroughBulkHelpers() {
        var oldIssue=issue(4);var oldLine=line(oldIssue);var oldDetail=detail(oldLine);
        assertThrows(ServiceException.class,()->details.deleteProductIssueDetailByIssueId(oldIssue.getId()));
        assertThrows(ServiceException.class,()->details.deleteProductIssueDetailByLineId(oldLine.getId()));
        assertThrows(ServiceException.class,()->lines.deleteProductIssueLineByIssueId(oldIssue.getId()));
        assertNotNull(detailMapper.selectById(oldDetail.getId()));assertNotNull(lineMapper.selectById(oldLine.getId()));
    }
    @Test void evenEditableDetailCannotChangeItsOriginalLineOrDocument() {
        var parent=issue(2);var first=line(parent);var second=line(parent);var picked=detail(first);
        var request=BeanUtils.toBean(picked,MesWmProductIssueDetailSaveReqVO.class);request.setLineId(second.getId());
        assertThrows(ServiceException.class,()->details.updateProductIssueDetail(request));assertEquals(first.getId(),detailMapper.selectById(picked.getId()).getLineId());
    }
    @Test void legitimateDraftLineAndPickingDetailEditsStillWork() {
        var draft=issue(0);var draftLine=line(draft);var lineRequest=BeanUtils.toBean(draftLine,MesWmProductIssueLineSaveReqVO.class);lineRequest.setQuantity(new BigDecimal("7"));lines.updateProductIssueLine(lineRequest);
        assertEquals(0,new BigDecimal("7").compareTo(lineMapper.selectById(draftLine.getId()).getQuantity()));
        var picking=issue(2);var pickedLine=line(picking);var picked=detail(pickedLine);var detailRequest=BeanUtils.toBean(picked,MesWmProductIssueDetailSaveReqVO.class);detailRequest.setQuantity(new BigDecimal("7"));details.updateProductIssueDetail(detailRequest);
        assertEquals(0,new BigDecimal("7").compareTo(detailMapper.selectById(picked.getId()).getQuantity()));
        details.deleteProductIssueDetail(picked.getId());assertNull(detailMapper.selectById(picked.getId()));
    }
    @Test void newRowsRequireCorrectParentStateAndBinding() {
        for(int status:new int[]{0,3,4,5}) {
            var parent=issue(status);var item=line(parent);var request=BeanUtils.toBean(detail(item),MesWmProductIssueDetailSaveReqVO.class);request.setId(null);
            assertThrows(ServiceException.class,()->details.createProductIssueDetail(request));
        }
        var picking=issue(2);var item=line(picking);var req=BeanUtils.toBean(detail(item),MesWmProductIssueDetailSaveReqVO.class);req.setId(null);assertNotNull(details.createProductIssueDetail(req));
        var foreign=issue(2);req.setIssueId(foreign.getId());assertThrows(ServiceException.class,()->details.createProductIssueDetail(req));
        var lineReq=BeanUtils.toBean(item,MesWmProductIssueLineSaveReqVO.class);lineReq.setId(null);assertThrows(ServiceException.class,()->lines.createProductIssueLine(lineReq));
        var draft=issue(0);lineReq.setIssueId(draft.getId());assertNotNull(lines.createProductIssueLine(lineReq));
    }
    @Test void completedSingleDeletesAreRejectedAndOriginalRowsRemain() {
        var parent=issue(4);var item=line(parent);var picked=detail(item);
        assertThrows(ServiceException.class,()->details.deleteProductIssueDetail(picked.getId()));assertThrows(ServiceException.class,()->lines.deleteProductIssueLine(item.getId()));assertThrows(ServiceException.class,()->issues.deleteProductIssue(parent.getId()));
        assertNotNull(detailMapper.selectById(picked.getId()));assertNotNull(lineMapper.selectById(item.getId()));assertNotNull(issueMapper.selectById(parent.getId()));
    }
    @Test void directBulkLineDeleteCascadesAndRollsBackWithItsTransaction() {
        var parent=issue(0);var item=line(parent);var picked=detail(item);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{lines.deleteProductIssueLineByIssueId(parent.getId());throw new IllegalStateException("downstream failure");}));
        assertNotNull(detailMapper.selectById(picked.getId()));assertNotNull(lineMapper.selectById(item.getId()));
        lines.deleteProductIssueLineByIssueId(parent.getId());assertNull(detailMapper.selectById(picked.getId()));assertNull(lineMapper.selectById(item.getId()));assertNotNull(issueMapper.selectById(parent.getId()));
    }
    @Test void draftHeaderDeleteAtomicallyRemovesBothChildLevels() {
        var parent=issue(0);var item=line(parent);var picked=detail(item);issues.deleteProductIssue(parent.getId());
        assertNull(issueMapper.selectById(parent.getId()));assertNull(lineMapper.selectById(item.getId()));assertNull(detailMapper.selectById(picked.getId()));
    }
    @Test void draftHeaderCannotChangeWorkOrderAfterLinesExistButEmptyDraftCan() {
        var parent=issue(0);line(parent);var request=BeanUtils.toBean(parent,MesWmProductIssueSaveReqVO.class);request.setRequiredTime(LocalDateTime.now());request.setWorkOrderId(200L);
        assertThrows(ServiceException.class,()->issues.updateProductIssue(request));assertEquals(100L,issueMapper.selectById(parent.getId()).getWorkOrderId());
        var empty=issue(0);request.setId(empty.getId());request.setCode(empty.getCode());issues.updateProductIssue(request);assertEquals(200L,issueMapper.selectById(empty.getId()).getWorkOrderId());
    }
    @Test void pickingAndPostingRejectCorruptOwnershipItemBatchAndNonPositiveQuantity() {
        for(String corrupt:new String[]{"issue","line","item","batch","quantity"}) {
            var parent=issue(2);var item=line(parent);var picked=detail(item);
            switch(corrupt){case "issue"->detailMapper.updateById(new MesWmProductIssueDetailDO().setId(picked.getId()).setIssueId(999999L));case "line"->detailMapper.updateById(new MesWmProductIssueDetailDO().setId(picked.getId()).setLineId(999999L));case "item"->detailMapper.updateById(new MesWmProductIssueDetailDO().setId(picked.getId()).setItemId(8L));case "batch"->lineMapper.updateById(new MesWmProductIssueLineDO().setId(item.getId()).setBatchId(99L));case "quantity"->detailMapper.updateById(new MesWmProductIssueDetailDO().setId(picked.getId()).setQuantity(BigDecimal.ZERO));}
            assertThrows(ServiceException.class,()->issues.stockProductIssue(parent.getId()));issueMapper.updateById(new MesWmProductIssueDO().setId(parent.getId()).setStatus(3));assertThrows(ServiceException.class,()->issues.finishProductIssue(parent.getId()));
        }
        verifyNoInteractions(transactionService);
    }
    @Test void postingUsesVerifiedDetailsAndCompletedDocumentCannotPostTwice() {
        var parent=issue(2);var item=line(parent);var picked=detail(item);issues.stockProductIssue(parent.getId());issues.finishProductIssue(parent.getId());
        var requests=ArgumentCaptor.forClass(MesWmTransactionSaveReqDTO.class);verify(transactionService,times(2)).createTransaction(requests.capture());
        assertEquals(0,BigDecimal.TEN.negate().compareTo(requests.getAllValues().get(0).getQuantity()));assertEquals(0,BigDecimal.TEN.compareTo(requests.getAllValues().get(1).getQuantity()));
        assertEquals(item.getId(),requests.getAllValues().get(0).getBizLineId());assertEquals(picked.getMaterialStockId(),requests.getAllValues().get(0).getMaterialStockId());assertEquals(900L,requests.getAllValues().get(1).getRelatedTransactionId());
        assertEquals(4,issueMapper.selectById(parent.getId()).getStatus());assertThrows(ServiceException.class,()->issues.finishProductIssue(parent.getId()));verify(transactionService,times(2)).createTransaction(any());
        verify(kittingService).lockForIssue(100L,java.util.List.of(103L,3L));verify(kittingService).releaseExcessAfterIssue(100L);
    }
    @Test void postingFailureRollsBackHeaderAndKeepsHistoricalPickingRows() {
        var parent=issue(3);var item=line(parent);var picked=detail(item);when(transactionService.createTransaction(any())).thenThrow(new IllegalStateException("stock quality or reservation conflict"));
        assertThrows(IllegalStateException.class,()->issues.finishProductIssue(parent.getId()));assertEquals(3,issueMapper.selectById(parent.getId()).getStatus());assertNotNull(lineMapper.selectById(item.getId()));assertNotNull(detailMapper.selectById(picked.getId()));verify(kittingService,never()).releaseExcessAfterIssue(any());
    }
}
