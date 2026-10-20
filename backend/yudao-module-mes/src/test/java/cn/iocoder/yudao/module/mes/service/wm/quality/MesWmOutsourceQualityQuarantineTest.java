package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.outsourcereceipt.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.outsourcereceipt.MesWmOutsourceReceiptMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmOutsourceReceiptStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.outsourcereceipt.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.outsourcereceipt.MesWmOutsourceReceiptLineMapper;
import cn.iocoder.yudao.module.mes.controller.admin.wm.outsourcereceipt.vo.line.MesWmOutsourceReceiptLineSaveReqVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MesWmOutsourceQualityQuarantineTest {
    @Test void failedOutsourceReceiptCannotEnterNormalStock() {
        var f = new Fixture();
        assertThrows(ServiceException.class, () -> f.service.finishOutsourceReceipt(1L));
        verifyNoInteractions(f.posting);
    }

    @Test void failedOutsourceReceiptCanEnterOnlyQuarantineAndQualifiedStockCanStillUseNormalWarehouse() {
        var f = new Fixture();
        f.detail.setWarehouseId(20L);
        f.service.finishOutsourceReceipt(1L);
        verify(f.posting).createTransactionList(argThat(rows -> rows.size() == 1
                && rows.get(0).getWarehouseId().equals(20L) && rows.get(0).getBizType() == 121));
        var pass = new Fixture();
        pass.line.setQualityStatus(1);
        pass.service.finishOutsourceReceipt(1L);
        verify(pass.posting).createTransactionList(argThat(rows -> rows.get(0).getWarehouseId().equals(2L)));
    }

    @Test void pendingQualityAndMismatchedBatchCannotBePassedByForgingReadyStatus() {
        var f = new Fixture();
        f.line.setQualityStatus(0);
        f.detail.setWarehouseId(20L);
        assertThrows(ServiceException.class, () -> f.service.finishOutsourceReceipt(1L));
        f.line.setQualityStatus(1);
        f.detail.setBatchId(99L);
        assertThrows(ServiceException.class, () -> f.service.finishOutsourceReceipt(1L));
        verifyNoInteractions(f.posting);
    }

    @Test void entirelyRejectedIqcUpdatesOriginalRowInsteadOfLeavingPendingQuantityAndCreatingDuplicate() {
        var f = new Fixture();
        f.header.setStatus(MesWmOutsourceReceiptStatusEnum.CONFIRMED.getStatus());
        f.line.setQualityStatus(0);
        f.service.updateOutsourceReceiptWhenIqcFinish(1L, 11L, 91L, BigDecimal.ZERO, BigDecimal.TEN);
        verify(f.lines).updateOutsourceReceiptLineDO(argThat(row -> row.getId().equals(11L)
                && row.getQualityStatus() == 2 && row.getQuantity().compareTo(BigDecimal.TEN) == 0));
        verify(f.lines, never()).createOutsourceReceiptLineDO(any());
        verify(f.mapper).updateById(argThat((MesWmOutsourceReceiptDO row) -> row.getStatus().equals(MesWmOutsourceReceiptStatusEnum.APPROVING.getStatus())));
    }

    @Test void anotherPendingLineKeepsWholeLotWaitingForInspectionAndForeignSourceIsRejected() {
        var f = new Fixture();
        f.header.setStatus(MesWmOutsourceReceiptStatusEnum.CONFIRMED.getStatus());
        f.line.setQualityStatus(0);
        when(f.lines.getOutsourceReceiptLineListByReceiptIdForUpdate(1L)).thenReturn(List.of(
                new MesWmOutsourceReceiptLineDO().setId(12L).setQualityStatus(0)));
        f.service.updateOutsourceReceiptWhenIqcFinish(1L, 11L, 91L, BigDecimal.ZERO, BigDecimal.TEN);
        verify(f.mapper, never()).updateById(any(MesWmOutsourceReceiptDO.class));
        f.line.setReceiptId(999L);
        assertThrows(ServiceException.class, () -> f.service.updateOutsourceReceiptWhenIqcFinish(
                1L, 11L, 91L, BigDecimal.ZERO, BigDecimal.TEN));
    }

    @Test void inspectedLineCannotBeEditedToDisableIqcAndEraseFailure() {
        var mapper = mock(MesWmOutsourceReceiptLineMapper.class);
        var parentMapper = mock(MesWmOutsourceReceiptMapper.class);
        var service = new MesWmOutsourceReceiptLineServiceImpl();
        ReflectionTestUtils.setField(service, "lineMapper", mapper);
        ReflectionTestUtils.setField(service, "documentMapper", parentMapper);
        when(mapper.selectById(11L)).thenReturn(new MesWmOutsourceReceiptLineDO().setId(11L).setReceiptId(1L).setQualityStatus(2));
        when(parentMapper.selectForUpdate(1L)).thenReturn(new MesWmOutsourceReceiptDO().setId(1L)
                .setStatus(MesWmOutsourceReceiptStatusEnum.APPROVING.getStatus()));
        assertThrows(ServiceException.class, () -> service.updateOutsourceReceiptLine(new MesWmOutsourceReceiptLineSaveReqVO()
                .setId(11L).setReceiptId(1L).setIqcCheckFlag(false)));
        verify(mapper, never()).updateById(any(MesWmOutsourceReceiptLineDO.class));
    }

    @Test void contradictoryFailedIqcCannotRewriteOutsourceSourceAsAllQualified() {
        var mapper = mock(cn.iocoder.yudao.module.mes.dal.mysql.qc.iqc.MesQcIqcMapper.class);
        var service = new cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcServiceImpl();
        ReflectionTestUtils.setField(service, "iqcMapper", mapper);
        when(mapper.selectById(91L)).thenReturn(new cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO()
                .setId(91L).setStatus(cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum.DRAFT.getStatus())
                .setSourceDocType(121).setSourceDocId(1L).setSourceLineId(11L).setCheckResult(2)
                .setQualifiedQuantity(BigDecimal.TEN).setUnqualifiedQuantity(BigDecimal.ZERO));
        assertThrows(ServiceException.class, () -> service.finishIqc(91L));
        verify(mapper, never()).updateById(any(cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO.class));
    }

    private static class Fixture {
        final MesWmOutsourceReceiptMapper mapper = mock(MesWmOutsourceReceiptMapper.class);
        final MesWmOutsourceReceiptLineService lines = mock(MesWmOutsourceReceiptLineService.class);
        final MesWmOutsourceReceiptDetailService details = mock(MesWmOutsourceReceiptDetailService.class);
        final MesWmTransactionService posting = mock(MesWmTransactionService.class);
        final MesWmWarehouseService warehouses = mock(MesWmWarehouseService.class);
        final MesWmOutsourceReceiptServiceImpl service = new MesWmOutsourceReceiptServiceImpl();
        final MesWmOutsourceReceiptDO header = new MesWmOutsourceReceiptDO().setId(1L).setCode("OUTSOURCE")
                .setStatus(MesWmOutsourceReceiptStatusEnum.APPROVED.getStatus());
        final MesWmOutsourceReceiptLineDO line = new MesWmOutsourceReceiptLineDO().setId(11L).setReceiptId(1L)
                .setItemId(7L).setBatchId(9L).setQuantity(BigDecimal.TEN).setQualityStatus(2);
        final MesWmOutsourceReceiptDetailDO detail = new MesWmOutsourceReceiptDetailDO().setId(21L).setLineId(11L)
                .setReceiptId(1L).setItemId(7L).setBatchId(9L).setQuantity(BigDecimal.TEN)
                .setWarehouseId(2L).setLocationId(3L).setAreaId(4L);
        Fixture() {
            ReflectionTestUtils.setField(service, "outsourceReceiptMapper", mapper);
            ReflectionTestUtils.setField(service, "outsourceReceiptLineService", lines);
            ReflectionTestUtils.setField(service, "outsourceReceiptDetailService", details);
            ReflectionTestUtils.setField(service, "wmTransactionService", posting);
            ReflectionTestUtils.setField(service, "warehouseService", warehouses);
            when(mapper.selectForUpdate(1L)).thenReturn(header);
            when(lines.getOutsourceReceiptLineListByReceiptId(1L)).thenReturn(List.of(line));
            when(lines.getOutsourceReceiptLineForUpdate(11L)).thenReturn(line);
            when(details.getOutsourceReceiptDetailListByReceiptId(1L)).thenReturn(List.of(detail));
            when(warehouses.validateWarehouseExists(anyLong())).thenAnswer(i -> new MesWmWarehouseDO()
                    .setId(i.getArgument(0)).setCode(Long.valueOf(20L).equals(i.getArgument(0))
                            ? MesWmQualityPolicy.QUARANTINE_WAREHOUSE : "NORMAL"));
        }
    }
}
