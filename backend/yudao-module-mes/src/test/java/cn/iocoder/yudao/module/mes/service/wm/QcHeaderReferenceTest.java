package cn.iocoder.yudao.module.mes.service.wm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.MesWmItemReceiptSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt.MesWmItemReceiptMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductSalesStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.client.MesMdClientService;
import cn.iocoder.yudao.module.mes.service.md.vendor.MesMdVendorService;
import cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcService;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcService;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.itemreceipt.MesWmItemReceiptServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.productsales.*;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeFulfillmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QcHeaderReferenceTest {
    @InjectMocks private MesWmItemReceiptServiceImpl receiptService;
    @InjectMocks private MesWmProductSalesServiceImpl salesService;
    @Mock private MesWmItemReceiptMapper receiptMapper;
    @Mock private MesWmProductSalesMapper salesMapper;
    @Mock private MesMdVendorService vendorService;
    @Mock private MesMdClientService clientService;
    @Mock private MesQcIqcService iqcService;
    @Mock private MesQcOqcService oqcService;
    @Mock private MesWmArrivalNoticeService arrivalNoticeService;
    @Mock private MesWmSalesNoticeService salesNoticeService;
    @Mock private MesWmProductSalesLineService productSalesLineService;
    @Mock private MesWmSalesNoticeFulfillmentService fulfillmentService;

    @Test
    void receiptAcceptsCompletedIqcOfSameVendor() {
        when(iqcService.validateIqcExists(5L)).thenReturn(new MesQcIqcDO().setVendorId(1L)
                .setStatus(MesQcStatusEnum.FINISHED.getStatus()));
        receiptService.createItemReceipt(receipt());
        verify(receiptMapper).insert(argThat((MesWmItemReceiptDO row) -> row.getIqcId().equals(5L)));
    }

    @Test
    void receiptRejectsIqcOfDifferentVendor() {
        when(iqcService.validateIqcExists(5L)).thenReturn(new MesQcIqcDO().setVendorId(2L)
                .setStatus(MesQcStatusEnum.FINISHED.getStatus()));
        assertEquals(WM_ITEM_RECEIPT_IQC_VENDOR_MISMATCH.getCode(),
                assertThrows(ServiceException.class, () -> receiptService.createItemReceipt(receipt())).getCode());
        verify(receiptMapper, never()).insert(any(MesWmItemReceiptDO.class));
    }

    @Test
    void receiptRejectsDraftIqc() {
        when(iqcService.validateIqcExists(5L)).thenReturn(new MesQcIqcDO().setVendorId(1L)
                .setStatus(MesQcStatusEnum.DRAFT.getStatus()));
        assertEquals(WM_ITEM_RECEIPT_IQC_NOT_FINISHED.getCode(),
                assertThrows(ServiceException.class, () -> receiptService.createItemReceipt(receipt())).getCode());
        verify(receiptMapper, never()).insert(any(MesWmItemReceiptDO.class));
    }

    @Test
    void receiptWithoutReferenceRemainsSupported() {
        receiptService.createItemReceipt(receipt().setIqcId(null));
        verifyNoInteractions(iqcService);
        verify(receiptMapper).insert(any(MesWmItemReceiptDO.class));
    }

    @Test
    void headerIqcCannotBypassPendingArrivalNoticeInspection() {
        when(arrivalNoticeService.validateArrivalNoticeReadyForReceipt(7L))
                .thenThrow(new ServiceException(WM_ARRIVAL_NOTICE_IQC_PENDING));
        assertThrows(ServiceException.class, () -> receiptService.createItemReceipt(receipt().setNoticeId(7L)));
        verify(receiptMapper, never()).insert(any(MesWmItemReceiptDO.class));
    }

    @Test
    void salesAcceptsCompletedOqcOfSameClient() {
        when(oqcService.validateOqcExists(5L)).thenReturn(new MesQcOqcDO().setClientId(1L)
                .setStatus(MesQcStatusEnum.FINISHED.getStatus()));
        salesService.createProductSales(sales());
        verify(salesMapper).insert(argThat((MesWmProductSalesDO row) -> row.getOqcId().equals(5L)));
    }

    @Test
    void salesRejectsOqcOfDifferentClient() {
        when(oqcService.validateOqcExists(5L)).thenReturn(new MesQcOqcDO().setClientId(2L)
                .setStatus(MesQcStatusEnum.FINISHED.getStatus()));
        assertEquals(WM_PRODUCT_SALES_OQC_CLIENT_MISMATCH.getCode(),
                assertThrows(ServiceException.class, () -> salesService.createProductSales(sales())).getCode());
        verify(salesMapper, never()).insert(any(MesWmProductSalesDO.class));
    }

    @Test
    void salesRejectsDraftOqc() {
        when(oqcService.validateOqcExists(5L)).thenReturn(new MesQcOqcDO().setClientId(1L)
                .setStatus(MesQcStatusEnum.DRAFT.getStatus()));
        assertEquals(WM_PRODUCT_SALES_OQC_NOT_FINISHED.getCode(),
                assertThrows(ServiceException.class, () -> salesService.createProductSales(sales())).getCode());
        verify(salesMapper, never()).insert(any(MesWmProductSalesDO.class));
    }

    @Test
    void salesWithoutReferenceRemainsSupported() {
        salesService.createProductSales(sales().setOqcId(null));
        verifyNoInteractions(oqcService);
        verify(salesMapper).insert(any(MesWmProductSalesDO.class));
    }

    @Test
    void completedHeaderOqcDoesNotExemptMandatoryLineInspection() {
        when(salesMapper.selectForUpdate(1L)).thenReturn(new MesWmProductSalesDO().setId(1L).setOqcId(5L)
                .setStatus(MesWmProductSalesStatusEnum.PREPARE.getStatus()));
        when(fulfillmentService.lockSales(1L)).thenAnswer(invocation->salesMapper.selectForUpdate(1L));
        when(productSalesLineService.getProductSalesLineListBySalesId(1L)).thenReturn(List.of(
                new MesWmProductSalesLineDO().setId(2L).setOqcCheckFlag(true).setQuantity(BigDecimal.ONE)));
        salesService.submitProductSales(1L);
        verify(salesMapper).updateById(argThat((MesWmProductSalesDO row) ->
                MesWmProductSalesStatusEnum.CONFIRMED.getStatus().equals(row.getStatus())));
        verify(productSalesLineService).updateProductSalesLineQualityStatus(eq(List.of(2L)), anyInt());
    }

    private MesWmItemReceiptSaveReqVO receipt() {
        return new MesWmItemReceiptSaveReqVO().setCode("RCPT-1").setVendorId(1L).setIqcId(5L);
    }
    private MesWmProductSalesSaveReqVO sales() {
        return new MesWmProductSalesSaveReqVO().setCode("SALES-1").setClientId(1L).setOqcId(5L);
    }
}
