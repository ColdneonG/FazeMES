package cn.iocoder.yudao.module.mes.service.wm.itemreceipt;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.line.MesWmItemReceiptLineSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.MesWmItemReceiptSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemBatchConfigDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt.*;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmItemReceiptStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemBatchConfigService;
import cn.iocoder.yudao.module.mes.service.md.vendor.MesMdVendorService;
import cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcService;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeLineService;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real H2 mappers and transaction proxies; external warehouse/model services stay isolated. */
@Import({MesWmItemReceiptBatchInvariantTest.ObservedReceiptService.class,
        MesWmItemReceiptLineServiceImpl.class, MesWmItemReceiptDetailServiceImpl.class,
        MesWmItemReceiptBatchInvariantTest.Transactions.class})
class MesWmItemReceiptBatchInvariantTest extends BaseDbUnitTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    /** Barrier fires after the preliminary line read, immediately before taking the real parent lock. */
    public static class ObservedReceiptService extends MesWmItemReceiptServiceImpl {
        private volatile CountDownLatch beforeEditable;
        private volatile CountDownLatch beforeHeaderUpdate;
        public void observeEditable(CountDownLatch barrier) {
            beforeEditable = barrier;
        }
        public void observeHeaderUpdate(CountDownLatch barrier) {
            beforeHeaderUpdate = barrier;
        }
        @Override
        @Transactional(rollbackFor = Exception.class)
        public void updateItemReceipt(MesWmItemReceiptSaveReqVO request) {
            CountDownLatch barrier = beforeHeaderUpdate;
            if (barrier != null) {
                barrier.countDown();
            }
            super.updateItemReceipt(request);
        }
        @Override
        public MesWmItemReceiptDO validateItemReceiptEditable(Long id) {
            CountDownLatch barrier = beforeEditable;
            if (barrier != null) {
                barrier.countDown();
            }
            return super.validateItemReceiptEditable(id);
        }
    }

    @Resource private ObservedReceiptService receipts;
    @Resource private MesWmItemReceiptLineService lines;
    @Resource private MesWmItemReceiptMapper receiptMapper;
    @Resource private MesWmItemReceiptLineMapper lineMapper;
    @Resource private MesWmItemReceiptDetailMapper detailMapper;
    @Resource private PlatformTransactionManager transactionManager;
    @MockitoBean private MesMdItemService itemService;
    @MockitoBean private MesWmBatchService batchService;
    @MockitoBean private MesMdItemBatchConfigService itemBatchConfigService;
    @MockitoBean private MesMdVendorService vendorService;
    @MockitoBean private MesQcIqcService iqcService;
    @MockitoBean private MesWmArrivalNoticeService arrivalNoticeService;
    @MockitoBean private MesWmArrivalNoticeLineService arrivalNoticeLineService;
    @MockitoBean private MesWmWarehouseAreaService warehouseAreaService;
    @MockitoBean private MesWmMaterialStockService materialStockService;
    @MockitoBean private MesWmTransactionService transactionService;
    @MockitoBean private cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService warehouseService;

    @BeforeEach
    void prepareItem() {
        when(itemService.validateItemExists(any())).thenAnswer(inv ->
                new MesMdItemDO().setId(inv.getArgument(0)).setBatchFlag(false));
        when(warehouseService.validateWarehouseExists(anyLong())).thenAnswer(inv ->
                new cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO()
                        .setId(inv.getArgument(0)).setCode(Long.valueOf(20L).equals(inv.getArgument(0))
                                ? "QUALITY_QUARANTINE_WAREHOUSE" : "RAW"));
    }

    private MesWmItemReceiptDO receipt(int status) {
        MesWmItemReceiptDO receipt = new MesWmItemReceiptDO().setCode(UUID.randomUUID().toString())
                .setName("采购入库").setVendorId(9L).setStatus(status)
                .setReceiptDate(LocalDateTime.of(2026, 2, 1, 0, 0)).setPurchaseOrderCode("PO-A");
        receiptMapper.insert(receipt);
        return receipt;
    }

    private MesWmItemReceiptLineDO line(MesWmItemReceiptDO receipt, Long batchId) {
        MesWmItemReceiptLineDO line = new MesWmItemReceiptLineDO().setReceiptId(receipt.getId())
                .setItemId(7L).setBatchId(batchId).setBatchCode(batchId == null ? null : "B" + batchId)
                .setReceivedQuantity(BigDecimal.TEN).setLotNumber("LOT-A")
                .setProductionDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setExpireDate(LocalDateTime.of(2027, 1, 1, 0, 0));
        lineMapper.insert(line);
        return line;
    }

    private MesWmItemReceiptDetailDO detail(MesWmItemReceiptLineDO line, Long batchId) {
        MesWmItemReceiptDetailDO detail = new MesWmItemReceiptDetailDO().setReceiptId(line.getReceiptId())
                .setLineId(line.getId()).setItemId(line.getItemId()).setBatchId(batchId)
                .setQuantity(BigDecimal.TEN).setWarehouseId(1L).setLocationId(2L).setAreaId(3L);
        detailMapper.insert(detail);
        return detail;
    }

    private MesWmItemReceiptLineSaveReqVO request(MesWmItemReceiptLineDO line) {
        return BeanUtils.toBean(line, MesWmItemReceiptLineSaveReqVO.class);
    }

    private MesWmItemReceiptSaveReqVO headerRequest(MesWmItemReceiptDO receipt) {
        return BeanUtils.toBean(receipt, MesWmItemReceiptSaveReqVO.class);
    }

    @Test void failedIqcCannotEnterNormalStockEvenWhenReceiptWasAlreadyMarkedReady() {
        var receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        receiptMapper.updateById(new MesWmItemReceiptDO().setId(receipt.getId()).setIqcId(41L));
        detail(line(receipt, null), null);
        when(iqcService.validateIqcExists(41L)).thenReturn(new cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO()
                .setId(41L).setVendorId(9L).setItemId(7L).setStatus(cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum.FINISHED.getStatus()).setCheckResult(2));
        assertThrows(ServiceException.class, () -> receipts.finishItemReceipt(receipt.getId()));
        verifyNoInteractions(transactionService);
        assertEquals(MesWmItemReceiptStatusEnum.APPROVED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
    }

    @Test void failedIqcCanOnlyPostToQuarantineAndUnknownOrMismatchedResultIsRejected() {
        var receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        receiptMapper.updateById(new MesWmItemReceiptDO().setId(receipt.getId()).setIqcId(41L));
        var detail = detail(line(receipt, null), null);
        detailMapper.updateById(new MesWmItemReceiptDetailDO().setId(detail.getId()).setWarehouseId(20L));
        var iqc = new cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO().setId(41L).setVendorId(9L)
                .setItemId(7L).setStatus(cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum.FINISHED.getStatus());
        when(iqcService.validateIqcExists(41L)).thenReturn(iqc);
        assertThrows(ServiceException.class, () -> receipts.finishItemReceipt(receipt.getId()));
        iqc.setCheckResult(2).setItemId(8L);
        assertThrows(ServiceException.class, () -> receipts.finishItemReceipt(receipt.getId()));
        verifyNoInteractions(transactionService);
        iqc.setItemId(7L);
        receipts.finishItemReceipt(receipt.getId());
        verify(transactionService).createTransactionList(argThat(rows -> rows.size() == 1 && rows.get(0).getWarehouseId().equals(20L)));
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
    }

    @Test
    void headerBatchInputsCannotChangeAfterFirstLineEvenBeforeAllocation() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        line(receipt, 20L);
        for (String field : new String[]{"vendor", "date", "order"}) {
            MesWmItemReceiptSaveReqVO req = headerRequest(receipt);
            switch (field) {
                case "vendor" -> req.setVendorId(10L);
                case "date" -> req.setReceiptDate(receipt.getReceiptDate().plusDays(1));
                case "order" -> req.setPurchaseOrderCode(null);
            }
            assertServiceException(() -> receipts.updateItemReceipt(req), WM_ITEM_RECEIPT_HEADER_HAS_LINE);
        }
        MesWmItemReceiptDO persisted = receiptMapper.selectById(receipt.getId());
        assertEquals(9L, persisted.getVendorId());
        assertEquals(receipt.getReceiptDate(), persisted.getReceiptDate());
        assertEquals("PO-A", persisted.getPurchaseOrderCode());
        verifyNoInteractions(vendorService, batchService);
    }

    @Test
    void headerNameAndRemarkCanChangeWithAllocatedLines() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        MesWmItemReceiptLineDO line = line(receipt, 20L);
        detail(line, 20L);
        MesWmItemReceiptSaveReqVO req = headerRequest(receipt).setName("入库验收").setRemark("checked");
        receipts.updateItemReceipt(req);
        MesWmItemReceiptDO persisted = receiptMapper.selectById(receipt.getId());
        assertEquals("入库验收", persisted.getName());
        assertEquals("checked", persisted.getRemark());
        assertEquals(9L, persisted.getVendorId());
        assertEquals(20L, lineMapper.selectById(line.getId()).getBatchId());
        verifyNoInteractions(batchService);
    }

    @Test
    void headerBatchInputsCanChangeBeforeFirstLineIncludingClearingPurchaseOrder() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        MesWmItemReceiptSaveReqVO req = headerRequest(receipt).setVendorId(10L)
                .setReceiptDate(receipt.getReceiptDate().plusDays(1)).setPurchaseOrderCode(null);
        receipts.updateItemReceipt(req);
        MesWmItemReceiptDO persisted = receiptMapper.selectById(receipt.getId());
        assertEquals(10L, persisted.getVendorId());
        assertEquals(req.getReceiptDate(), persisted.getReceiptDate());
        assertNull(persisted.getPurchaseOrderCode());
    }

    @Test
    void allocatedMaterialAndAllBatchInputsCannotChange() {
        MesWmItemReceiptLineDO line = line(receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus()), 20L);
        detail(line, 20L);
        for (String field : new String[]{"item", "production", "expiry", "lot"}) {
            MesWmItemReceiptLineSaveReqVO req = request(line);
            switch (field) {
                case "item" -> req.setItemId(8L);
                case "production" -> req.setProductionDate(null);
                case "expiry" -> req.setExpireDate(line.getExpireDate().plusDays(1));
                case "lot" -> req.setLotNumber("LOT-B");
            }
            assertServiceException(() -> lines.updateItemReceiptLine(req), WM_ITEM_RECEIPT_LINE_HAS_DETAIL);
        }
        assertEquals("LOT-A", lineMapper.selectById(line.getId()).getLotNumber());
        assertEquals(20L, detailMapper.selectListByLineId(line.getId()).get(0).getBatchId());
        verifyNoInteractions(batchService);
    }

    @Test
    void quantityAndRemarkEditCanBeRetriedWithoutRegeneratingBatch() {
        MesWmItemReceiptLineDO line = line(receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus()), 20L);
        detail(line, 20L);
        MesWmItemReceiptLineSaveReqVO req = request(line);
        req.setRemark("verified");
        req.setReceivedQuantity(new BigDecimal("12"));
        lines.updateItemReceiptLine(req);
        lines.updateItemReceiptLine(req);
        assertEquals(20L, lineMapper.selectById(line.getId()).getBatchId());
        assertEquals("verified", lineMapper.selectById(line.getId()).getRemark());
        verifyNoInteractions(batchService);
        req.setReceivedQuantity(new BigDecimal("9"));
        assertServiceException(() -> lines.updateItemReceiptLine(req), WM_ITEM_RECEIPT_DETAIL_QUANTITY_EXCEED);
        assertEquals(0, new BigDecimal("12").compareTo(lineMapper.selectById(line.getId()).getReceivedQuantity()));
    }

    @Test
    void beforeAllocationBatchCanChangeAndNonBatchItemClearsOldBatchAndDates() {
        MesWmItemReceiptLineDO line = line(receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus()), 20L);
        MesWmItemReceiptLineSaveReqVO req = request(line);
        req.setLotNumber("LOT-B");
        when(batchService.getOrGenerateBatchCode(any())).thenReturn(new MesWmBatchDO().setId(21L).setCode("B21"));
        lines.updateItemReceiptLine(req);
        assertEquals(21L, lineMapper.selectById(line.getId()).getBatchId());
        req.setItemId(8L);
        req.setProductionDate(null);
        req.setExpireDate(null);
        req.setLotNumber(null);
        when(batchService.getOrGenerateBatchCode(any())).thenReturn(null);
        lines.updateItemReceiptLine(req);
        MesWmItemReceiptLineDO updated = lineMapper.selectById(line.getId());
        assertNull(updated.getBatchId());
        assertNull(updated.getBatchCode());
        assertNull(updated.getProductionDate());
        assertNull(updated.getExpireDate());
        assertNull(updated.getLotNumber());
    }

    @Test
    void completedReceiptCannotBeEditedBySpoofingParent() {
        MesWmItemReceiptDO closed = receipt(MesWmItemReceiptStatusEnum.FINISHED.getStatus());
        MesWmItemReceiptLineDO line = line(closed, 20L);
        MesWmItemReceiptLineSaveReqVO req = request(line);
        req.setReceiptId(receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus()).getId());
        assertServiceException(() -> lines.updateItemReceiptLine(req), WM_ITEM_RECEIPT_STATUS_NOT_PREPARE);
        assertEquals(closed.getId(), lineMapper.selectById(line.getId()).getReceiptId());
        verifyNoInteractions(batchService);
    }

    @Test
    void finishRejectsStaleBatchEvenWhenQuantitiesAndItemsMatch() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        MesWmItemReceiptLineDO line = line(receipt, 20L);
        detail(line, 19L);
        assertServiceException(() -> receipts.finishItemReceipt(receipt.getId()), WM_ITEM_RECEIPT_DETAIL_BATCH_MISMATCH);
        assertEquals(MesWmItemReceiptStatusEnum.APPROVED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        verifyNoInteractions(transactionService, arrivalNoticeService);
    }

    @Test
    void stockAlsoRejectsStaleBatchBeforeApproving() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVING.getStatus());
        detail(line(receipt, 20L), null);
        assertServiceException(() -> receipts.stockItemReceipt(receipt.getId()), WM_ITEM_RECEIPT_DETAIL_BATCH_MISMATCH);
        assertEquals(MesWmItemReceiptStatusEnum.APPROVING.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        verifyNoInteractions(transactionService);
    }

    @Test
    void finishRejectsRequiredMissingBatchAndDeletedOrUnrelatedBatch() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        MesWmItemReceiptLineDO line = line(receipt, null);
        detail(line, null);
        when(itemService.validateItemExists(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        assertServiceException(() -> receipts.finishItemReceipt(receipt.getId()), MD_ITEM_BATCH_REQUIRED);
        lineMapper.updateById(new MesWmItemReceiptLineDO().setId(line.getId()).setBatchId(20L));
        MesWmItemReceiptDetailDO detail = detailMapper.selectListByLineId(line.getId()).get(0);
        detailMapper.updateById(new MesWmItemReceiptDetailDO().setId(detail.getId()).setBatchId(20L));
        when(batchService.validateBatchExists(20L, 7L)).thenThrow(new ServiceException(WM_BATCH_NOT_EXISTS));
        assertServiceException(() -> receipts.finishItemReceipt(receipt.getId()), WM_BATCH_NOT_EXISTS);
        verifyNoInteractions(transactionService);
    }

    @Test
    void nonBatchLineAndDetailCanFinishWithBothBatchesNullAndCannotRepeat() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        detail(line(receipt, null), null);
        receipts.finishItemReceipt(receipt.getId());
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        verify(transactionService, times(1)).createTransactionList(any());
        verifyNoInteractions(batchService);
        assertServiceException(() -> receipts.finishItemReceipt(receipt.getId()), WM_ITEM_RECEIPT_STATUS_ERROR);
        verify(transactionService, times(1)).createTransactionList(any());
    }

    @Test
    void finishRejectsLegacyDriftInEveryEnabledHeaderAndLineBatchAttribute() {
        when(itemService.validateItemExists(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        when(itemBatchConfigService.validateItemBatchConfigExists(7L)).thenReturn(new MesMdItemBatchConfigDO()
                .setVendorFlag(true).setReceiptDateFlag(true).setPurchaseOrderCodeFlag(true)
                .setProduceDateFlag(true).setExpireDateFlag(true).setLotNumberFlag(true));
        for (String field : new String[]{"vendor", "receiptDate", "order", "production", "expiry", "lot"}) {
            MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
            MesWmItemReceiptLineDO line = line(receipt, 20L);
            detail(line, 20L);
            MesWmBatchDO batch = new MesWmBatchDO().setId(20L).setItemId(7L).setVendorId(receipt.getVendorId())
                    .setReceiptDate(receipt.getReceiptDate()).setPurchaseOrderCode(receipt.getPurchaseOrderCode())
                    .setProduceDate(line.getProductionDate()).setExpireDate(line.getExpireDate()).setLotNumber(line.getLotNumber());
            switch (field) {
                case "vendor" -> batch.setVendorId(10L);
                case "receiptDate" -> batch.setReceiptDate(batch.getReceiptDate().plusDays(1));
                case "order" -> batch.setPurchaseOrderCode("PO-B");
                case "production" -> batch.setProduceDate(batch.getProduceDate().plusDays(1));
                case "expiry" -> batch.setExpireDate(batch.getExpireDate().plusDays(1));
                case "lot" -> batch.setLotNumber("LOT-B");
            }
            when(batchService.validateBatchExists(20L, 7L)).thenReturn(batch);
            assertServiceException(() -> receipts.finishItemReceipt(receipt.getId()), WM_ITEM_RECEIPT_BATCH_ATTRIBUTES_MISMATCH);
            assertEquals(MesWmItemReceiptStatusEnum.APPROVED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        }
        verifyNoInteractions(transactionService);
        verify(batchService, never()).getOrGenerateBatchCode(any());
    }

    @Test
    void disabledBatchAttributesDoNotRequireVendorDatesOrOrderOnBatch() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        detail(line(receipt, 20L), 20L);
        when(itemService.validateItemExists(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        when(itemBatchConfigService.validateItemBatchConfigExists(7L)).thenReturn(new MesMdItemBatchConfigDO());
        when(batchService.validateBatchExists(20L, 7L)).thenReturn(new MesWmBatchDO().setId(20L).setItemId(7L));
        receipts.finishItemReceipt(receipt.getId());
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        verify(transactionService).createTransactionList(any());
        verify(batchService, never()).getOrGenerateBatchCode(any());
    }

    @Test
    void matchingEnabledBatchAttributesCanFinish() {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
        MesWmItemReceiptLineDO line = line(receipt, 20L);
        detail(line, 20L);
        when(itemService.validateItemExists(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));
        when(itemBatchConfigService.validateItemBatchConfigExists(7L)).thenReturn(new MesMdItemBatchConfigDO()
                .setVendorFlag(true).setReceiptDateFlag(true).setPurchaseOrderCodeFlag(true)
                .setProduceDateFlag(true).setExpireDateFlag(true).setLotNumberFlag(true));
        when(batchService.validateBatchExists(20L, 7L)).thenReturn(new MesWmBatchDO().setId(20L).setItemId(7L)
                .setVendorId(receipt.getVendorId()).setReceiptDate(receipt.getReceiptDate())
                .setPurchaseOrderCode(receipt.getPurchaseOrderCode()).setProduceDate(line.getProductionDate())
                .setExpireDate(line.getExpireDate()).setLotNumber(line.getLotNumber()));
        receipts.finishItemReceipt(receipt.getId());
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), receiptMapper.selectById(receipt.getId()).getStatus());
        verify(transactionService).createTransactionList(any());
        verify(batchService, never()).getOrGenerateBatchCode(any());
    }

    @Test
    void waitingHeaderEditorSeesCommittedFirstLineBeforeChangingBatchInputs() throws Exception {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        MesWmItemReceiptSaveReqVO request = headerRequest(receipt).setVendorId(10L);
        CountDownLatch beforeUpdate = new CountDownLatch(1);
        receipts.observeHeaderUpdate(beforeUpdate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?>[] editor = new Future<?>[1];
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                receiptMapper.selectForUpdate(receipt.getId());
                editor[0] = executor.submit(() -> receipts.updateItemReceipt(request));
                try {
                    assertTrue(beforeUpdate.await(10, TimeUnit.SECONDS), "editor must reach parent lock");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(editor[0].isDone(), "header editor must wait for the held parent lock");
                line(receipt, 20L);
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> editor[0].get(10, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(WM_ITEM_RECEIPT_HEADER_HAS_LINE.getCode(), ((ServiceException) failure.getCause()).getCode());
            assertEquals(9L, receiptMapper.selectById(receipt.getId()).getVendorId());
            verifyNoInteractions(vendorService, batchService);
        } finally {
            receipts.observeHeaderUpdate(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void waitingLineEditorRereadsCommittedLineAndAllocationUnderParentLock() throws Exception {
        MesWmItemReceiptDO receipt = receipt(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        MesWmItemReceiptLineDO line = line(receipt, 20L);
        MesWmItemReceiptLineSaveReqVO stale = request(line);
        CountDownLatch beforeEditable = new CountDownLatch(1);
        receipts.observeEditable(beforeEditable);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?>[] editor = new Future<?>[1];
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                receiptMapper.selectForUpdate(receipt.getId());
                editor[0] = executor.submit(() -> lines.updateItemReceiptLine(stale));
                try {
                    assertTrue(beforeEditable.await(10, TimeUnit.SECONDS), "editor must reach parent lock");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(editor[0].isDone(), "editor cannot cross the held parent lock");
                lineMapper.updateById(new MesWmItemReceiptLineDO().setId(line.getId()).setLotNumber("LOT-B").setBatchId(21L));
                detail(line, 21L);
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> editor[0].get(10, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(WM_ITEM_RECEIPT_LINE_HAS_DETAIL.getCode(), ((ServiceException) failure.getCause()).getCode());
            assertEquals("LOT-B", lineMapper.selectById(line.getId()).getLotNumber());
            assertEquals(21L, detailMapper.selectListByLineId(line.getId()).get(0).getBatchId());
            verifyNoInteractions(batchService);
        } finally {
            receipts.observeEditable(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
