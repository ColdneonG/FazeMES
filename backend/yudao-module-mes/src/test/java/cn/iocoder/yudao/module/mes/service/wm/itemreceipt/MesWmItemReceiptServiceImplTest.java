package cn.iocoder.yudao.module.mes.service.wm.itemreceipt;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDetailDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt.MesWmItemReceiptMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmItemReceiptStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.vendor.MesMdVendorService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemBatchConfigService;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import org.junit.jupiter.api.BeforeEach;
import cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcService;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jakarta.annotation.Resource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link MesWmItemReceiptServiceImpl} 的单元测试
 *
 * @author 芋道源码
 */
@Import(MesWmItemReceiptServiceImpl.class)
public class MesWmItemReceiptServiceImplTest extends BaseDbUnitTest {
    @MockitoBean private cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService warehouseService;

    @Resource
    private MesWmItemReceiptServiceImpl itemReceiptService;

    @Resource
    private MesWmItemReceiptMapper itemReceiptMapper;

    @MockitoBean
    private MesWmItemReceiptLineService itemReceiptLineService;
    @MockitoBean
    private MesWmItemReceiptDetailService itemReceiptDetailService;
    @MockitoBean
    private MesWmArrivalNoticeService arrivalNoticeService;
    @MockitoBean
    private MesMdVendorService vendorService;
    @MockitoBean
    private MesQcIqcService iqcService;
    @MockitoBean
    private MesWmTransactionService wmTransactionService;
    @MockitoBean private MesMdItemService itemService;
    @MockitoBean private MesWmBatchService batchService;
    @MockitoBean private MesMdItemBatchConfigService itemBatchConfigService;
    @BeforeEach void prepareItem() {
        when(itemService.validateItemExists(any())).thenAnswer(inv -> new MesMdItemDO().setId(inv.getArgument(0)).setBatchFlag(false));
    }

    // ========== finishItemReceipt ==========

    @Test
    public void testFinishItemReceipt_success_withNoticeId() {
        // mock 数据：插入一条待入库状态的入库单（有关联到货通知单）
        Long noticeId = randomLongId();
        MesWmItemReceiptDO receipt = randomPojo(MesWmItemReceiptDO.class, o -> {
            o.setStatus(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
            o.setNoticeId(noticeId);
        });
        itemReceiptMapper.insert(receipt);
        Long receiptId = receipt.getId();

        // mock 明细列表
        MesWmItemReceiptDetailDO detail1 = randomPojo(MesWmItemReceiptDetailDO.class, o -> {
            o.setReceiptId(receiptId);
            o.setQuantity(BigDecimal.valueOf(100));
        });
        MesWmItemReceiptDetailDO detail2 = randomPojo(MesWmItemReceiptDetailDO.class, o -> {
            o.setReceiptId(receiptId);
            o.setQuantity(BigDecimal.valueOf(50));
        });
        detail1.setLineId(1001L).setItemId(9001L).setBatchId(null);
        detail2.setLineId(1002L).setItemId(9002L).setBatchId(null);
        when(itemReceiptLineService.getItemReceiptLineListByReceiptIdForUpdate(receiptId)).thenReturn(Arrays.asList(
                new MesWmItemReceiptLineDO().setId(1001L).setReceiptId(receiptId).setItemId(9001L).setReceivedQuantity(BigDecimal.valueOf(100)),
                new MesWmItemReceiptLineDO().setId(1002L).setReceiptId(receiptId).setItemId(9002L).setReceivedQuantity(BigDecimal.valueOf(50))));
        when(itemReceiptDetailService.getItemReceiptDetailListByReceiptIdForUpdate(eq(receiptId)))
                .thenReturn(Arrays.asList(detail1, detail2));

        // 调用
        itemReceiptService.finishItemReceipt(receiptId);

        // 断言 1：状态更新为已完成
        MesWmItemReceiptDO updated = itemReceiptMapper.selectById(receiptId);
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), updated.getStatus());
        // 断言 2：库存事务被创建（2 条明细）
        verify(wmTransactionService).createTransactionList(anyList());
        // 断言 3：到货通知单被完成
        verify(arrivalNoticeService).finishArrivalNotice(eq(noticeId));
    }

    @Test
    public void testFinishItemReceipt_success_withoutNoticeId() {
        // mock 数据：插入一条待入库状态的入库单（无关联到货通知单）
        MesWmItemReceiptDO receipt = randomPojo(MesWmItemReceiptDO.class, o -> {
            o.setStatus(MesWmItemReceiptStatusEnum.APPROVED.getStatus());
            o.setNoticeId(null); // 无到货通知单
        });
        itemReceiptMapper.insert(receipt);
        Long receiptId = receipt.getId();

        // A successful receipt still needs a real, balanced line/detail even without an arrival notice.
        when(itemReceiptLineService.getItemReceiptLineListByReceiptIdForUpdate(receiptId)).thenReturn(Collections.singletonList(
                new MesWmItemReceiptLineDO().setId(1003L).setReceiptId(receiptId).setItemId(9003L).setReceivedQuantity(BigDecimal.valueOf(30))));
        when(itemReceiptDetailService.getItemReceiptDetailListByReceiptIdForUpdate(eq(receiptId))).thenReturn(Collections.singletonList(
                new MesWmItemReceiptDetailDO().setId(2003L).setReceiptId(receiptId).setLineId(1003L).setItemId(9003L).setQuantity(BigDecimal.valueOf(30))));

        // 调用
        itemReceiptService.finishItemReceipt(receiptId);

        // 断言 1：状态更新为已完成
        MesWmItemReceiptDO updated = itemReceiptMapper.selectById(receiptId);
        assertEquals(MesWmItemReceiptStatusEnum.FINISHED.getStatus(), updated.getStatus());
        // 断言 2：平衡的入库明细创建库存事务
        verify(wmTransactionService).createTransactionList(anyList());
        // 断言 3：到货通知单完成方法不被调用
        verify(arrivalNoticeService, never()).finishArrivalNotice(any());
    }

    @Test
    public void testFinishItemReceipt_notExists() {
        // 准备参数
        Long receiptId = randomLongId();

        // 调用，并断言异常
        assertServiceException(
                () -> itemReceiptService.finishItemReceipt(receiptId),
                WM_ITEM_RECEIPT_NOT_EXISTS);
    }

    @Test
    public void testFinishItemReceipt_statusNotApproved() {
        // mock 数据：插入一条草稿状态
        MesWmItemReceiptDO receipt = randomPojo(MesWmItemReceiptDO.class, o -> {
            o.setStatus(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        });
        itemReceiptMapper.insert(receipt);

        // 调用，并断言异常
        assertServiceException(
                () -> itemReceiptService.finishItemReceipt(receipt.getId()),
                WM_ITEM_RECEIPT_STATUS_ERROR);
    }

    @Test
    public void testFinishItemReceipt_statusAlreadyFinished() {
        // mock 数据：插入一条已完成状态
        MesWmItemReceiptDO receipt = randomPojo(MesWmItemReceiptDO.class, o -> {
            o.setStatus(MesWmItemReceiptStatusEnum.FINISHED.getStatus());
        });
        itemReceiptMapper.insert(receipt);

        // 调用，并断言异常
        assertServiceException(
                () -> itemReceiptService.finishItemReceipt(receipt.getId()),
                WM_ITEM_RECEIPT_STATUS_ERROR);
    }

    @Test
    public void testFinishItemReceipt_emptyLinesCannotComplete() {
        MesWmItemReceiptDO receipt=randomPojo(MesWmItemReceiptDO.class,o->{
            o.setStatus(MesWmItemReceiptStatusEnum.APPROVED.getStatus());o.setNoticeId(null);
        });
        itemReceiptMapper.insert(receipt);
        when(itemReceiptLineService.getItemReceiptLineListByReceiptIdForUpdate(receipt.getId())).thenReturn(Collections.emptyList());
        assertServiceException(()->itemReceiptService.finishItemReceipt(receipt.getId()),WM_ITEM_RECEIPT_DETAIL_QUANTITY_MISMATCH);
        assertEquals(MesWmItemReceiptStatusEnum.APPROVED.getStatus(),itemReceiptMapper.selectById(receipt.getId()).getStatus());
        verifyNoInteractions(wmTransactionService,arrivalNoticeService);
    }
}
