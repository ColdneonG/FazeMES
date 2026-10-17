package cn.iocoder.yudao.module.mes.service.pro.feedback;

import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.itemconsume.MesWmItemConsumeService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceLineService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Progress regression tests that do not start Spring or a database. */
@ExtendWith(MockitoExtension.class)
class MesProFeedbackProgressTest {

    @InjectMocks
    private MesProFeedbackServiceImpl service;
    @Mock private MesProFeedbackMapper mapper;
    @Mock private MesProRouteProcessService routeProcessService;
    @Mock private MesProTaskService taskService;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesWmItemConsumeService itemConsumeService;
    @Mock private MesWmProductProduceService productProduceService;
    @Mock private MesWmProductProduceLineService produceLineService;

    @Test
    void nonKeyApprovalAdvancesTaskOnceWithoutProducingWorkOrderUnits() {
        MesProFeedbackDO feedback = prepare(false, false);
        doAnswer(invocation -> {
            MesProFeedbackDO update = invocation.getArgument(0);
            feedback.setStatus(update.getStatus());
            return 1;
        }).when(mapper).updateById(any(MesProFeedbackDO.class));

        assertTrue(service.approveFeedback(1L));
        verify(taskService).updateProducedQuantity(2L, new BigDecimal("50"),
                new BigDecimal("45"), new BigDecimal("5"));
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
        verifyNoInteractions(productProduceService, produceLineService);
        assertEquals(MesProFeedbackStatusEnum.FINISHED.getStatus(), feedback.getStatus());

        // A repeated request must fail the existing status check before any
        // additional material consumption or task progress is recorded.
        assertThrows(RuntimeException.class, () -> service.approveFeedback(1L));
        verify(taskService, times(1)).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(itemConsumeService, times(1)).generateItemConsume(feedback);
    }

    @Test
    void nonKeyApprovalWithNoClassifiedQuantitiesUsesZero() {
        MesProFeedbackDO feedback = prepare(false, true);
        feedback.setQualifiedQuantity(null).setUnqualifiedQuantity(null).setUncheckQuantity(new BigDecimal("50"));

        assertTrue(service.approveFeedback(1L));

        verify(taskService).updateProducedQuantity(2L, new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO);
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
        verifyNoInteractions(productProduceService, produceLineService);
        var order = inOrder(workOrderService, taskService);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(3L);
        order.verify(taskService).validateTaskNotFinishedForUpdate(2L, 3L);
        order.verify(taskService).updateProducedQuantity(anyLong(), any(), any(), any());
    }

    @Test
    void keyApprovalStillCountsWorkOrderOnlyOnceAndUsesOutputQuality() {
        prepare(true, false);
        when(productProduceService.generateProductProduce(any(), eq(false)))
                .thenReturn(new MesWmProductProduceDO().setId(20L));
        when(produceLineService.getProductProduceLineListByFeedbackId(1L)).thenReturn(List.of(
                new MesWmProductProduceLineDO().setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus()).setQuantity(new BigDecimal("40")),
                new MesWmProductProduceLineDO().setQualityStatus(MesWmQualityStatusEnum.FAIL.getStatus()).setQuantity(new BigDecimal("10"))));

        assertTrue(service.approveFeedback(1L));

        verify(taskService).updateProducedQuantity(2L, new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("10"));
        verify(workOrderService).updateProducedQuantity(3L, new BigDecimal("50"));
        var order = inOrder(workOrderService, taskService, productProduceService);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(3L);
        order.verify(taskService).validateTaskNotFinishedForUpdate(2L, 3L);
        order.verify(productProduceService).generateProductProduce(any(), eq(false));
        order.verify(taskService).updateProducedQuantity(anyLong(), any(), any(), any());
        order.verify(workOrderService).updateProducedQuantity(anyLong(), any());
    }

    @Test
    void keyQualityCheckDefersBothProgressCountersUntilInspectionFinishes() {
        prepare(true, true).setUncheckQuantity(new BigDecimal("50"));

        assertFalse(service.approveFeedback(1L));

        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
        verify(productProduceService).generateProductProduce(any(), eq(true));
    }

    @Test
    void failedTaskProgressDoesNotMarkFeedbackFinished() {
        prepare(false, false);
        doThrow(new IllegalStateException("progress write failed")).when(taskService)
                .updateProducedQuantity(anyLong(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.approveFeedback(1L));

        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
    }

    @Test
    void ipqcCallbackLocksWorkOrderBeforeTaskOutputAndProgress() {
        MesProFeedbackDO feedback = prepare(true, true);
        feedback.setStatus(MesProFeedbackStatusEnum.UNCHECK.getStatus());
        when(produceLineService.getProductProduceLineListByFeedbackId(1L)).thenReturn(List.of(
                new MesWmProductProduceLineDO().setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus()).setQuantity(new BigDecimal("50"))));

        service.updateProFeedbackWhenIpqcFinish(1L, 30L, new BigDecimal("50"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var order = inOrder(workOrderService, taskService, productProduceService);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(3L);
        order.verify(taskService).validateTaskExistsForUpdate(2L, 3L);
        order.verify(productProduceService).splitPendingAndFinishProduce(eq(1L), eq(30L), any(), any());
        order.verify(taskService).updateProducedQuantity(anyLong(), any(), any(), any());
        order.verify(workOrderService).updateProducedQuantity(anyLong(), any());
        verify(taskService, never()).validateTaskNotFinishedForUpdate(anyLong(), anyLong());
    }

    @Test
    void approvalRechecksFeedbackStatusAfterWaitingForWorkOrderLock() {
        prepare(false, false);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(new MesProFeedbackDO().setId(1L)
                .setWorkOrderId(3L).setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus()));

        assertThrows(RuntimeException.class, () -> service.approveFeedback(1L));

        verifyNoInteractions(itemConsumeService, taskService, productProduceService);
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
    }

    @Test
    void approvalRejectsTaskMovedAwayFromLockedWorkOrderBeforeAnyStockChange() {
        prepare(false, false);
        doThrow(new IllegalStateException("task now belongs to a different work order"))
                .when(taskService).validateTaskNotFinishedForUpdate(2L, 3L);

        assertThrows(IllegalStateException.class, () -> service.approveFeedback(1L));

        verifyNoInteractions(itemConsumeService, productProduceService);
        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
    }

    private MesProFeedbackDO prepare(boolean key, boolean check) {
        MesProFeedbackDO feedback = new MesProFeedbackDO().setId(1L).setTaskId(2L).setWorkOrderId(3L)
                .setRouteId(4L).setProcessId(5L).setStatus(MesProFeedbackStatusEnum.APPROVING.getStatus())
                .setFeedbackQuantity(new BigDecimal("50")).setQualifiedQuantity(new BigDecimal("45"))
                .setUnqualifiedQuantity(new BigDecimal("5")).setUncheckQuantity(BigDecimal.ZERO);
        when(mapper.selectById(1L)).thenReturn(feedback);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(feedback);
        lenient().when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(key).setCheckFlag(check));
        return feedback;
    }
}
