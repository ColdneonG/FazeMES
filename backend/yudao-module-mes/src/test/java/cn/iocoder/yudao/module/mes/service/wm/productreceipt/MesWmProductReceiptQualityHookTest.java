package cn.iocoder.yudao.module.mes.service.wm.productreceipt;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productreceipt.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productreceipt.MesWmProductReceiptMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductReceiptStatusEnum;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies the quality quota protects the real stock-posting entry point. */
@ExtendWith(MockitoExtension.class)
class MesWmProductReceiptQualityHookTest {
    @InjectMocks private MesWmProductReceiptServiceImpl service;
    @Mock private MesWmProductReceiptMapper mapper;
    @Mock private MesWmProductReceiptLineService lineService;
    @Mock private MesWmProductReceiptDetailService detailService;
    @Mock private MesProductReceiptQualityService qualityService;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesWmTransactionService wmTransactionService;
    @Mock private MesWmWarehouseService warehouseService;
    @Mock private MesWmWarehouseLocationService locationService;
    @Mock private MesWmWarehouseAreaService areaService;

    @Test
    void qualityRejectionHappensAfterOrderLockAndBeforeEitherStockTransfer() {
        var receipt = new MesWmProductReceiptDO().setId(1L).setWorkOrderId(2L)
                .setStatus(MesWmProductReceiptStatusEnum.APPROVED.getStatus());
        when(mapper.selectById(1L)).thenReturn(receipt);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(receipt);
        var line = new MesWmProductReceiptLineDO().setId(11L).setQuantity(new BigDecimal("100"));
        var detail = new MesWmProductReceiptDetailDO().setId(12L).setLineId(11L).setItemId(100L)
                .setBatchId(7L).setQuantity(new BigDecimal("100"));
        when(lineService.getProductReceiptLineListByRecptId(1L)).thenReturn(List.of(line));
        when(detailService.getProductReceiptDetailListByRecptId(1L)).thenReturn(List.of(detail));
        when(detailService.getProductReceiptDetailListByLineId(11L)).thenReturn(List.of(detail));
        when(warehouseService.getWarehouseByCode(anyString())).thenReturn(new MesWmWarehouseDO().setId(20L));
        when(locationService.getWarehouseLocationByCode(anyString())).thenReturn(new MesWmWarehouseLocationDO().setId(21L));
        when(areaService.getWarehouseAreaByCode(anyString())).thenReturn(new MesWmWarehouseAreaDO().setId(22L));
        doThrow(new ServiceException(1_047_160_010, "合格产出不足"))
                .when(qualityService).validateAvailable(2L, 100L, 7L, new BigDecimal("100"));

        assertThrows(ServiceException.class, () -> service.finishProductReceipt(1L));

        var locks = inOrder(mapper, workOrderService, qualityService);
        locks.verify(mapper).selectById(1L);
        locks.verify(workOrderService).validateWorkOrderExistsForUpdate(2L);
        locks.verify(mapper).selectByIdForUpdate(1L);
        locks.verify(qualityService).validateAvailable(2L, 100L, 7L, new BigDecimal("100"));
        verifyNoInteractions(wmTransactionService);
        verify(mapper, never()).updateById(any(MesWmProductReceiptDO.class));
    }

    @Test
    void changedSourceOrderDuringLockWaitCannotUseTheWrongSerializationLock() {
        when(mapper.selectById(1L)).thenReturn(new MesWmProductReceiptDO().setId(1L).setWorkOrderId(2L));
        when(mapper.selectByIdForUpdate(1L)).thenReturn(new MesWmProductReceiptDO().setId(1L).setWorkOrderId(3L)
                .setStatus(MesWmProductReceiptStatusEnum.APPROVED.getStatus()));

        assertThrows(ServiceException.class, () -> service.finishProductReceipt(1L));

        verify(workOrderService).validateWorkOrderExistsForUpdate(2L);
        verifyNoInteractions(wmTransactionService, qualityService, lineService, detailService);
    }
}
