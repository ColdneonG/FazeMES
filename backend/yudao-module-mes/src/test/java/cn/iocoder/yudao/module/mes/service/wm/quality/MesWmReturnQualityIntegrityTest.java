package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnissue.vo.detail.MesWmReturnIssueDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.detail.MesWmReturnSalesDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.wm.*;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.*;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real return-service entry points with isolated collaborators; stock/SQL tests cover posting itself. */
class MesWmReturnQualityIntegrityTest {
    @BeforeEach void tenant(){TenantContextHolder.setTenantId(1L);}
    @AfterEach void clearTenant(){TenantContextHolder.clear();}

    private static final long PARENT = 1L;
    private static final long LINE = 11L;
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final int POLICY_ERROR = 1_047_180_001;

    @Test
    void salesLegacyNullSourceDocumentAdvancesActualLockedParentAfterInspection() {
        var f = sales();
        when(f.mapper.selectListByReturnIdForUpdate(PARENT)).thenReturn(List.of(salesLine(MesWmQualityStatusEnum.PASS)));
        f.service.updateReturnSalesLineWhenRqcFinish(LINE, null, 1, TEN, BigDecimal.ZERO);
        var update = ArgumentCaptor.forClass(MesWmReturnSalesLineDO.class);
        verify(f.mapper).updateById(update.capture());
        assertEquals(LINE, update.getValue().getId());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), update.getValue().getQualityStatus());
        verify(f.parent).updateReturnSalesStatus(PARENT, MesWmReturnSalesStatusEnum.APPROVING.getStatus());
        verify(f.mapper).selectListByReturnIdForUpdate(PARENT);
        verify(f.mapper, never()).selectListByReturnId(anyLong());
        var order = inOrder(f.mapper, f.parent);
        order.verify(f.mapper).selectById(LINE);
        order.verify(f.parent).validateReturnSalesExists(PARENT);
        order.verify(f.mapper).selectByIdForUpdate(LINE);
    }

    @Test
    void issueLegacyNullSourceDocumentSplitsAndAdvancesActualLockedParent() {
        var f = issue();
        f.inspection.setCheckResult(2).setQualifiedQuantity(new BigDecimal("6")).setUnqualifiedQuantity(new BigDecimal("4"));
        when(f.mapper.selectListByIssueIdForUpdate(PARENT)).thenReturn(List.of(issueLine(MesWmQualityStatusEnum.PASS),
                new MesWmReturnIssueLineDO().setId(12L).setQualityStatus(MesWmQualityStatusEnum.FAIL.getStatus())));
        f.service.updateReturnIssueLineWhenRqcFinish(LINE, null, 2, new BigDecimal("6"), new BigDecimal("4"));
        var insert = ArgumentCaptor.forClass(MesWmReturnIssueLineDO.class);
        var update = ArgumentCaptor.forClass(MesWmReturnIssueLineDO.class);
        verify(f.mapper).insert(insert.capture());
        verify(f.mapper).updateById(update.capture());
        assertEquals(PARENT, insert.getValue().getIssueId());
        assertEquals(50L, insert.getValue().getMaterialStockId());
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), insert.getValue().getQualityStatus());
        assertEquals(0, new BigDecimal("4").compareTo(insert.getValue().getQuantity()));
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), update.getValue().getQualityStatus());
        assertEquals(0, new BigDecimal("6").compareTo(update.getValue().getQuantity()));
        verify(f.parent).updateReturnIssueStatus(PARENT, MesWmReturnIssueStatusEnum.APPROVING.getStatus());
        verify(f.mapper).selectListByIssueIdForUpdate(PARENT);
        verify(f.mapper, never()).selectListByIssueId(anyLong());
        var order = inOrder(f.mapper, f.parent);
        order.verify(f.mapper).selectById(LINE);
        order.verify(f.parent).validateReturnIssueExists(PARENT);
        order.verify(f.mapper).selectByIdForUpdate(LINE);
    }

    @Test
    void salesCallbackKeepsParentConfirmedWhileAnotherLineIsPending() {
        var f = sales();
        when(f.mapper.selectListByReturnIdForUpdate(PARENT)).thenReturn(List.of(salesLine(MesWmQualityStatusEnum.PASS),
                new MesWmReturnSalesLineDO().setId(12L).setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus())));
        f.service.updateReturnSalesLineWhenRqcFinish(LINE, null, 1, TEN, BigDecimal.ZERO);
        verify(f.mapper).updateById(any(MesWmReturnSalesLineDO.class));
        verify(f.parent, never()).updateReturnSalesStatus(anyLong(), anyInt());
    }

    @Test
    void currentSalesLineAfterParentLockRejectsStalePendingRead() {
        for (var actual : List.of(MesWmQualityStatusEnum.PASS, MesWmQualityStatusEnum.FAIL)) {
            var f = sales();
            when(f.mapper.selectByIdForUpdate(LINE)).thenReturn(salesLine(actual));
            // The parent is still CONFIRMED because a different line is waiting for inspection.
            expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> f.service
                    .updateReturnSalesLineWhenRqcFinish(LINE, PARENT, 1, TEN, BigDecimal.ZERO));
            verify(f.mapper, never()).updateById(any(MesWmReturnSalesLineDO.class));
            verify(f.mapper, never()).insert(any(MesWmReturnSalesLineDO.class));
            verify(f.parent, never()).updateReturnSalesStatus(anyLong(), anyInt());
            var order = inOrder(f.mapper, f.parent);
            order.verify(f.mapper).selectById(LINE);
            order.verify(f.parent).validateReturnSalesExists(PARENT);
            order.verify(f.mapper).selectByIdForUpdate(LINE);
        }
    }

    @Test
    void currentIssueLineAfterParentLockRejectsStalePendingRead() {
        for (var actual : List.of(MesWmQualityStatusEnum.PASS, MesWmQualityStatusEnum.FAIL)) {
            var f = issue();
            when(f.mapper.selectByIdForUpdate(LINE)).thenReturn(issueLine(actual));
            expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> f.service
                    .updateReturnIssueLineWhenRqcFinish(LINE, PARENT, 1, TEN, BigDecimal.ZERO));
            verify(f.mapper, never()).updateById(any(MesWmReturnIssueLineDO.class));
            verify(f.mapper, never()).insert(any(MesWmReturnIssueLineDO.class));
            verify(f.parent, never()).updateReturnIssueStatus(anyLong(), anyInt());
            var order = inOrder(f.mapper, f.parent);
            order.verify(f.mapper).selectById(LINE);
            order.verify(f.parent).validateReturnIssueExists(PARENT);
            order.verify(f.mapper).selectByIdForUpdate(LINE);
        }
    }

    @Test
    void salesInspectionRejectsNegativeNonconservingAndCrossDocumentResults() {
        for (BigDecimal[] split : invalidSplits()) {
            var f = sales();
            expectCode(POLICY_ERROR, () -> f.service.updateReturnSalesLineWhenRqcFinish(
                    LINE, PARENT, 2, split[0], split[1]));
            verify(f.mapper, never()).updateById(any(MesWmReturnSalesLineDO.class));
            verify(f.mapper, never()).insert(any(MesWmReturnSalesLineDO.class));
            verify(f.parent, never()).updateReturnSalesStatus(anyLong(), anyInt());
        }
        var f = sales();
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> f.service
                .updateReturnSalesLineWhenRqcFinish(LINE, 999L, 1, TEN, BigDecimal.ZERO));
        verify(f.mapper, never()).updateById(any(MesWmReturnSalesLineDO.class));
    }

    @Test
    void issueInspectionRejectsNegativeNonconservingAndCrossDocumentResults() {
        for (BigDecimal[] split : invalidSplits()) {
            var f = issue();
            expectCode(POLICY_ERROR, () -> f.service.updateReturnIssueLineWhenRqcFinish(
                    LINE, PARENT, 2, split[0], split[1]));
            verify(f.mapper, never()).updateById(any(MesWmReturnIssueLineDO.class));
            verify(f.mapper, never()).insert(any(MesWmReturnIssueLineDO.class));
            verify(f.parent, never()).updateReturnIssueStatus(anyLong(), anyInt());
        }
        var f = issue();
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> f.service
                .updateReturnIssueLineWhenRqcFinish(LINE, 999L, 1, TEN, BigDecimal.ZERO));
        verify(f.mapper, never()).updateById(any(MesWmReturnIssueLineDO.class));
    }

    @Test
    void completedSalesDetailCannotBeMovedToAnEditableParent() {
        var mapper = mock(MesWmReturnSalesDetailMapper.class);
        var parent = mock(MesWmReturnSalesService.class);
        var lines = mock(MesWmReturnSalesLineService.class);
        var service = new MesWmReturnSalesDetailServiceImpl();
        inject(service, "returnSalesDetailMapper", mapper, "returnSalesService", parent,
                "returnSalesLineService", lines,"sourceService",mock(MesWmReturnSalesSourceService.class));
        when(mapper.selectById(21L)).thenReturn(new MesWmReturnSalesDetailDO()
                .setId(21L).setReturnId(PARENT).setLineId(LINE));
        when(parent.validateReturnSalesExists(PARENT)).thenReturn(new MesWmReturnSalesDO()
                .setId(PARENT).setStatus(MesWmReturnSalesStatusEnum.FINISHED.getStatus()));
        when(parent.validateReturnSalesExists(2L)).thenReturn(new MesWmReturnSalesDO()
                .setId(2L).setStatus(MesWmReturnSalesStatusEnum.APPROVED.getStatus()));
        var req = new MesWmReturnSalesDetailSaveReqVO();
        req.setId(21L);
        req.setReturnId(2L);
        req.setLineId(12L);
        expectCode(WM_RETURN_SALES_STATUS_NOT_APPROVED.getCode(), () -> service.updateReturnSalesDetail(req));
        verify(parent).validateReturnSalesExists(PARENT);
        verify(parent, never()).validateReturnSalesExists(2L);
        verify(mapper, never()).updateById(any(MesWmReturnSalesDetailDO.class));
        verifyNoInteractions(lines);
    }

    @Test
    void editableSalesDetailStillCannotChangeItsParentOrLine() {
        var mapper = mock(MesWmReturnSalesDetailMapper.class);
        var parent = mock(MesWmReturnSalesService.class);
        var service = new MesWmReturnSalesDetailServiceImpl();
        inject(service, "returnSalesDetailMapper", mapper, "returnSalesService", parent,"sourceService",mock(MesWmReturnSalesSourceService.class));
        when(mapper.selectById(21L)).thenReturn(new MesWmReturnSalesDetailDO()
                .setId(21L).setReturnId(PARENT).setLineId(LINE));
        when(mapper.selectByIdForUpdate(21L)).thenReturn(new MesWmReturnSalesDetailDO()
                .setId(21L).setReturnId(PARENT).setLineId(LINE));
        when(parent.validateReturnSalesExists(PARENT)).thenReturn(new MesWmReturnSalesDO()
                .setId(PARENT).setStatus(MesWmReturnSalesStatusEnum.APPROVED.getStatus()));
        var req = new MesWmReturnSalesDetailSaveReqVO();
        req.setId(21L);
        req.setReturnId(PARENT);
        req.setLineId(12L);
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> service.updateReturnSalesDetail(req));
        verify(mapper, never()).updateById(any(MesWmReturnSalesDetailDO.class));
    }

    @Test
    void salesDetailRechecksOriginalOwnershipAfterWaitingForParent() {
        var mapper = mock(MesWmReturnSalesDetailMapper.class);
        var parent = mock(MesWmReturnSalesService.class);
        var service = new MesWmReturnSalesDetailServiceImpl();
        inject(service, "returnSalesDetailMapper", mapper, "returnSalesService", parent,"sourceService",mock(MesWmReturnSalesSourceService.class));
        when(mapper.selectById(21L)).thenReturn(new MesWmReturnSalesDetailDO().setId(21L).setReturnId(PARENT).setLineId(LINE));
        when(mapper.selectByIdForUpdate(21L)).thenReturn(new MesWmReturnSalesDetailDO().setId(21L).setReturnId(2L).setLineId(12L));
        when(parent.validateReturnSalesExists(PARENT)).thenReturn(new MesWmReturnSalesDO().setId(PARENT).setStatus(MesWmReturnSalesStatusEnum.APPROVED.getStatus()));
        var req = new MesWmReturnSalesDetailSaveReqVO();req.setId(21L);req.setReturnId(2L);req.setLineId(12L);
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> service.updateReturnSalesDetail(req));
        verify(parent, never()).validateReturnSalesExists(2L);
        verify(mapper, never()).updateById(any(MesWmReturnSalesDetailDO.class));
    }

    @Test
    void completedIssueDetailLocksOriginalParentBeforeRejectingMove() {
        var mapper = mock(MesWmReturnIssueDetailMapper.class);
        var parent = mock(MesWmReturnIssueMapper.class);
        var lines = mock(MesWmReturnIssueLineService.class);
        var service = new MesWmReturnIssueDetailServiceImpl();
        inject(service, "issueDetailMapper", mapper, "issueMapper", parent, "issueLineService", lines);
        when(mapper.selectById(21L)).thenReturn(new MesWmReturnIssueDetailDO()
                .setId(21L).setIssueId(PARENT).setLineId(LINE));
        when(parent.selectByIdForUpdate(PARENT)).thenReturn(new MesWmReturnIssueDO()
                .setId(PARENT).setStatus(MesWmReturnIssueStatusEnum.FINISHED.getStatus()));
        when(parent.selectByIdForUpdate(2L)).thenReturn(new MesWmReturnIssueDO()
                .setId(2L).setStatus(MesWmReturnIssueStatusEnum.APPROVING.getStatus()));
        var req = new MesWmReturnIssueDetailSaveReqVO();
        req.setId(21L);
        req.setIssueId(2L);
        req.setLineId(12L);
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> service.updateReturnIssueDetail(req));
        verify(parent).selectByIdForUpdate(PARENT);
        verify(parent, never()).selectByIdForUpdate(2L);
        verify(mapper, never()).updateById(any(MesWmReturnIssueDetailDO.class));
        verifyNoInteractions(lines);
    }

    @Test
    void editableIssueDetailStillCannotChangeItsParentOrLine() {
        var mapper = mock(MesWmReturnIssueDetailMapper.class);
        var parent = mock(MesWmReturnIssueMapper.class);
        var service = new MesWmReturnIssueDetailServiceImpl();
        inject(service, "issueDetailMapper", mapper, "issueMapper", parent);
        when(mapper.selectById(21L)).thenReturn(new MesWmReturnIssueDetailDO()
                .setId(21L).setIssueId(PARENT).setLineId(LINE));
        when(parent.selectByIdForUpdate(PARENT)).thenReturn(new MesWmReturnIssueDO()
                .setId(PARENT).setStatus(MesWmReturnIssueStatusEnum.APPROVING.getStatus()));
        var req = new MesWmReturnIssueDetailSaveReqVO();
        req.setId(21L);
        req.setIssueId(PARENT);
        req.setLineId(12L);
        expectCode(WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(), () -> service.updateReturnIssueDetail(req));
        verify(mapper, never()).updateById(any(MesWmReturnIssueDetailDO.class));
    }

    @Test
    void finalProductionReturnRejectsFailIntoNormalWarehouseBeforeAnyPosting() {
        var f = posting(MesWmQualityStatusEnum.FAIL, "NORMAL");
        expectCode(POLICY_ERROR, () -> f.service.finishReturnIssue(PARENT));
        verifyNoInteractions(f.transactions);
        verify(f.mapper, never()).updateById(any(MesWmReturnIssueDO.class));
    }

    @Test
    void finalProductionReturnPostsFailOnlyAsPairedWipOutAndQuarantineIn() {
        var f = posting(MesWmQualityStatusEnum.FAIL, MesWmQualityPolicy.QUARANTINE_WAREHOUSE);
        when(f.transactions.createTransaction(any(MesWmTransactionSaveReqDTO.class))).thenReturn(701L, 702L);
        f.service.finishReturnIssue(PARENT);
        var posted = ArgumentCaptor.forClass(MesWmTransactionSaveReqDTO.class);
        verify(f.transactions, times(2)).createTransaction(posted.capture());
        var out = posted.getAllValues().get(0);
        var in = posted.getAllValues().get(1);
        assertEquals(MesWmTransactionTypeEnum.OUT.getType(), out.getType());
        assertEquals(0, TEN.negate().compareTo(out.getQuantity()));
        assertEquals(90L, out.getWarehouseId());
        assertEquals(91L, out.getLocationId());
        assertEquals(92L, out.getAreaId());
        assertEquals(50L, out.getMaterialStockId());
        assertEquals(100L, out.getWorkOrderId());
        assertEquals(MesBizTypeConstants.WM_RETURN_ISSUE, out.getBizType());
        assertEquals(MesWmTransactionTypeEnum.IN.getType(), in.getType());
        assertEquals(0, TEN.compareTo(in.getQuantity()));
        assertEquals(20L, in.getWarehouseId());
        assertEquals(21L, in.getLocationId());
        assertEquals(22L, in.getAreaId());
        assertEquals(701L, in.getRelatedTransactionId());
        assertEquals(out.getItemId(), in.getItemId());
        assertEquals(out.getBatchId(), in.getBatchId());
        assertEquals(out.getBizId(), in.getBizId());
        assertEquals(out.getBizLineId(), in.getBizLineId());
        var finished = ArgumentCaptor.forClass(MesWmReturnIssueDO.class);
        verify(f.mapper).updateById(finished.capture());
        assertEquals(MesWmReturnIssueStatusEnum.FINISHED.getStatus(), finished.getValue().getStatus());
    }

    @Test
    void finalProductionReturnRejectsPendingEvenIntoQuarantine() {
        var f = posting(MesWmQualityStatusEnum.PENDING, MesWmQualityPolicy.QUARANTINE_WAREHOUSE);
        expectCode(POLICY_ERROR, () -> f.service.finishReturnIssue(PARENT));
        verifyNoInteractions(f.transactions);
        verify(f.mapper, never()).updateById(any(MesWmReturnIssueDO.class));
    }

    @Test
    void finalProductionReturnRejectsUnderOverAndNegativeDetails() {
        for (BigDecimal amount : List.of(new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("-1"))) {
            var f = posting(MesWmQualityStatusEnum.FAIL, MesWmQualityPolicy.QUARANTINE_WAREHOUSE);
            f.detail.setQuantity(amount);
            expectCode(WM_RETURN_ISSUE_DETAIL_QUANTITY_MISMATCH.getCode(), () -> f.service.finishReturnIssue(PARENT));
            verifyNoInteractions(f.transactions);
            verify(f.mapper, never()).updateById(any(MesWmReturnIssueDO.class));
        }
    }

    @Test
    void finalProductionReturnRejectsForgedSourceStockBatchOrLineBeforePosting() {
        for (String field : List.of("materialStockId", "batchId", "itemId", "lineId", "issueId")) {
            var f = posting(MesWmQualityStatusEnum.FAIL, MesWmQualityPolicy.QUARANTINE_WAREHOUSE);
            ReflectionTestUtils.setField(f.detail, field, 999L);
            expectCode(WM_RETURN_ISSUE_DETAIL_QUANTITY_MISMATCH.getCode(), () -> f.service.finishReturnIssue(PARENT));
            verifyNoInteractions(f.transactions);
        }
    }

    private Sales sales() {
        var mapper = mock(MesWmReturnSalesLineMapper.class);
        var parent = mock(MesWmReturnSalesService.class);
        var service = new MesWmReturnSalesLineServiceImpl();
        var jdbc=mock(JdbcTemplate.class);var qc=new java.util.LinkedHashMap<String,Object>();qc.put("id",88L);qc.put("status",4);qc.put("source_doc_type",119);qc.put("source_doc_id",PARENT);qc.put("source_line_id",LINE);qc.put("item_id",10L);qc.put("batch_code",null);qc.put("check_quantity",TEN);qc.put("qualified_quantity",TEN);qc.put("unqualified_quantity",BigDecimal.ZERO);qc.put("check_result",1);
        // Bind the Object... overload: untyped any() otherwise selects the Class<T> overload.
        when(jdbc.queryForList(anyString(), (Object) eq(1L), (Object) eq(PARENT), (Object) eq(LINE)))
                .thenReturn(List.of(qc));
        inject(service, "returnSalesLineMapper", mapper, "returnSalesService", parent,"sourceService",mock(MesWmReturnSalesSourceService.class),"jdbcTemplate",jdbc);
        when(mapper.selectById(LINE)).thenReturn(salesLine(MesWmQualityStatusEnum.PENDING));
        when(mapper.selectByIdForUpdate(LINE)).thenReturn(salesLine(MesWmQualityStatusEnum.PENDING));
        when(parent.validateReturnSalesExists(PARENT)).thenReturn(new MesWmReturnSalesDO()
                .setId(PARENT).setStatus(MesWmReturnSalesStatusEnum.CONFIRMED.getStatus()));
        return new Sales(service, mapper, parent);
    }

    private Issue issue() {
        var mapper = mock(MesWmReturnIssueLineMapper.class);
        var parent = mock(MesWmReturnIssueService.class);
        var service = new MesWmReturnIssueLineServiceImpl();
        var qcMapper=mock(MesQcRqcMapper.class);var qc=new MesQcRqcDO().setId(88L).setStatus(4).setSourceDocType(116).setSourceDocId(PARENT).setSourceLineId(LINE).setItemId(10L).setCheckQuantity(TEN).setQualifiedQuantity(TEN).setUnqualifiedQuantity(BigDecimal.ZERO).setCheckResult(1);
        when(qcMapper.selectOne(any(Wrapper.class))).thenReturn(qc);
        inject(service, "issueLineMapper", mapper, "issueService", parent,"rqcMapper",qcMapper);
        when(mapper.selectById(LINE)).thenReturn(issueLine(MesWmQualityStatusEnum.PENDING));
        when(mapper.selectByIdForUpdate(LINE)).thenReturn(issueLine(MesWmQualityStatusEnum.PENDING));
        when(parent.validateReturnIssueExists(PARENT)).thenReturn(new MesWmReturnIssueDO()
                .setId(PARENT).setStatus(MesWmReturnIssueStatusEnum.CONFIRMED.getStatus()));
        return new Issue(service, mapper, parent,qc);
    }

    private Posting posting(MesWmQualityStatusEnum quality, String warehouseCode) {
        var service = new MesWmReturnIssueServiceImpl();
        var mapper = mock(MesWmReturnIssueMapper.class);
        var lines = mock(MesWmReturnIssueLineService.class);
        var details = mock(MesWmReturnIssueDetailService.class);
        var transactions = mock(MesWmTransactionService.class);
        var warehouses = mock(MesWmWarehouseService.class);
        var locations = mock(MesWmWarehouseLocationService.class);
        var areas = mock(MesWmWarehouseAreaService.class);
        inject(service, "issueMapper", mapper, "issueLineService", lines, "issueDetailService", details,
                "wmTransactionService", transactions, "warehouseService", warehouses,
                "locationService", locations, "areaService", areas);
        when(mapper.selectByIdForUpdate(PARENT)).thenReturn(new MesWmReturnIssueDO().setId(PARENT)
                .setWorkOrderId(100L).setCode("RET-1").setStatus(MesWmReturnIssueStatusEnum.APPROVED.getStatus()));
        when(lines.getReturnIssueLineListByIssueId(PARENT)).thenReturn(List.of(issueLine(quality)));
        var detail = new MesWmReturnIssueDetailDO().setId(31L).setIssueId(PARENT).setLineId(LINE)
                .setMaterialStockId(50L).setItemId(10L).setBatchId(7L).setBatchCode("B7").setQuantity(TEN)
                .setWarehouseId(20L).setLocationId(21L).setAreaId(22L);
        when(details.getReturnIssueDetailListByIssueId(PARENT)).thenReturn(List.of(detail));
        when(warehouses.validateWarehouseExists(20L)).thenReturn(new MesWmWarehouseDO()
                .setId(20L).setCode(warehouseCode).setFrozen(false));
        when(warehouses.getWarehouseByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE)).thenReturn(
                new MesWmWarehouseDO().setId(90L).setCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE));
        when(locations.getWarehouseLocationByCode(MesWmWarehouseLocationDO.WIP_VIRTUAL_LOCATION)).thenReturn(
                new MesWmWarehouseLocationDO().setId(91L));
        when(areas.getWarehouseAreaByCode(MesWmWarehouseAreaDO.WIP_VIRTUAL_AREA)).thenReturn(
                new MesWmWarehouseAreaDO().setId(92L));
        return new Posting(service, mapper, transactions, detail);
    }

    private MesWmReturnSalesLineDO salesLine(MesWmQualityStatusEnum quality) {
        return new MesWmReturnSalesLineDO().setId(LINE).setReturnId(PARENT).setItemId(10L).setBatchId(7L)
                .setQuantity(TEN).setQualityStatus(quality.getStatus()).setOriginTransactionId(23L).setRqcId(88L).setRqcCheckFlag(true);
    }

    private MesWmReturnIssueLineDO issueLine(MesWmQualityStatusEnum quality) {
        return new MesWmReturnIssueLineDO().setId(LINE).setIssueId(PARENT).setItemId(10L).setBatchId(7L)
                .setMaterialStockId(50L).setQuantity(TEN).setQualityStatus(quality.getStatus()).setRqcId(88L).setRqcCheckFlag(true);
    }

    private List<BigDecimal[]> invalidSplits() {
        return List.of(new BigDecimal[]{new BigDecimal("-1"), new BigDecimal("11")},
                new BigDecimal[]{new BigDecimal("11"), new BigDecimal("-1")},
                new BigDecimal[]{new BigDecimal("9"), BigDecimal.ZERO},
                new BigDecimal[]{new BigDecimal("11"), BigDecimal.ZERO},
                new BigDecimal[]{null, null});
    }

    private void expectCode(int code, Runnable operation) {
        assertEquals(code, assertThrows(ServiceException.class, operation::run).getCode());
    }

    private static void inject(Object service, Object... namesAndValues) {
        for (int i = 0; i < namesAndValues.length; i += 2) {
            ReflectionTestUtils.setField(service, (String) namesAndValues[i], namesAndValues[i + 1]);
        }
    }

    private record Sales(MesWmReturnSalesLineServiceImpl service, MesWmReturnSalesLineMapper mapper,
                         MesWmReturnSalesService parent) { }
    private record Issue(MesWmReturnIssueLineServiceImpl service, MesWmReturnIssueLineMapper mapper,
                         MesWmReturnIssueService parent,MesQcRqcDO inspection) { }
    private record Posting(MesWmReturnIssueServiceImpl service, MesWmReturnIssueMapper mapper,
                           MesWmTransactionService transactions, MesWmReturnIssueDetailDO detail) { }
}
