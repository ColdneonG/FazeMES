package cn.iocoder.yudao.module.mes.service.pro.feedback;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.itemconsume.MesWmItemConsumeService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceLineService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.PRO_FEEDBACK_NOT_PREPARE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 row locks and Spring transactions; no application server or external database. */
@Import(MesProFeedbackServiceImpl.class)
class MesProFeedbackMutationConcurrencyTest extends BaseDbUnitTest {

    @MockitoBean private MesProFeedbackFlowService flowService;

    @Resource private MesProFeedbackServiceImpl service;
    @Resource private MesProFeedbackMapper mapper;
    @MockitoBean private MesProWorkOrderService workOrderService;
    @MockitoBean private MesProRouteProcessService routeProcessService;
    @MockitoBean private MesMdWorkstationService workstationService;
    @MockitoBean private MesProTaskService taskService;
    @MockitoBean private MesWmItemConsumeService itemConsumeService;
    @MockitoBean private MesWmProductProduceService productProduceService;
    @MockitoBean private MesWmProductProduceLineService produceLineService;
    @MockitoBean private MesProSopService sopService;

    @Test
    void editingCannotOverwriteAReportThatWasSubmittedWhileWaitingForTheLock() throws Exception {
        raceSubmissionWithMutation(false);
    }

    @Test
    void deletingCannotRemoveAReportThatWasSubmittedWhileWaitingForTheLock() throws Exception {
        raceSubmissionWithMutation(true);
    }

    private void raceSubmissionWithMutation(boolean delete) throws Exception {
        MesProFeedbackDO feedback = insertFeedback(MesProFeedbackStatusEnum.PREPARE);
        CountDownLatch submissionHoldsFeedbackLock = new CountDownLatch(1);
        CountDownLatch releaseSubmission = new CountDownLatch(1);
        CountDownLatch mutationReachedLockPath = new CountDownLatch(1);
        AtomicInteger orderLocks = new AtomicInteger();
        when(workOrderService.validateWorkOrderExistsForUpdate(3L)).thenAnswer(invocation -> {
            if (orderLocks.incrementAndGet() == 2) mutationReachedLockPath.countDown();
            return null;
        });
        when(workstationService.validateWorkstationAvailable(6L)).thenAnswer(invocation -> {
            submissionHoldsFeedbackLock.countDown();
            if (!releaseSubmission.await(5, TimeUnit.SECONDS)) throw new AssertionError("submission was not released");
            return new MesMdWorkstationDO().setId(6L);
        });
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(false).setCheckFlag(false));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var submission = pool.submit(() -> service.submitFeedback(feedback.getId()));
            assertTrue(submissionHoldsFeedbackLock.await(5, TimeUnit.SECONDS));
            var mutation = pool.submit(() -> {
                if (delete) {
                    service.deleteFeedback(feedback.getId());
                } else {
                    MesProFeedbackSaveReqVO req = new MesProFeedbackSaveReqVO();
                    req.setId(feedback.getId());
                    req.setWorkOrderId(3L);
                    req.setFeedbackQuantity(new BigDecimal("999"));
                    service.updateFeedback(req);
                }
            });
            assertTrue(mutationReachedLockPath.await(5, TimeUnit.SECONDS));
            // A plain status read followed by an unprotected write would finish here.
            assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));
            releaseSubmission.countDown();
            submission.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> mutation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(PRO_FEEDBACK_NOT_PREPARE.getCode(), ((ServiceException) failure.getCause()).getCode());

            MesProFeedbackDO saved = mapper.selectById(feedback.getId());
            assertNotNull(saved);
            assertEquals(MesProFeedbackStatusEnum.APPROVING.getStatus(), saved.getStatus());
            assertEquals(0, BigDecimal.TEN.compareTo(saved.getFeedbackQuantity()));
            verifyNoInteractions(itemConsumeService, productProduceService, produceLineService);
        } finally {
            releaseSubmission.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedInspectionProgressRollsBackTheFeedbackStatusAndQuantityWrite() {
        MesProFeedbackDO feedback = insertFeedback(MesProFeedbackStatusEnum.UNCHECK);
        when(produceLineService.getProductProduceLineListByFeedbackId(feedback.getId()))
                .thenReturn(List.of(new MesWmProductProduceLineDO().setQuantity(BigDecimal.TEN)
                        .setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus())));
        doThrow(new IllegalStateException("progress update failed"))
                .when(workOrderService).updateProducedQuantity(eq(3L), any());

        assertThrows(IllegalStateException.class, () -> service.updateProFeedbackWhenIpqcFinish(
                feedback.getId(), 30L, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        MesProFeedbackDO saved = mapper.selectById(feedback.getId());
        assertEquals(MesProFeedbackStatusEnum.UNCHECK.getStatus(), saved.getStatus());
        assertEquals(0, new BigDecimal("8").compareTo(saved.getQualifiedQuantity()));
        assertEquals(0, new BigDecimal("2").compareTo(saved.getUnqualifiedQuantity()));
    }

    @Test
    void productionPrecisionIsPreservedDuringTheDatabaseRoundTrip() {
        MesProFeedbackDO feedback = insertFeedback(MesProFeedbackStatusEnum.PREPARE);
        mapper.updateById(new MesProFeedbackDO().setId(feedback.getId())
                .setFeedbackQuantity(new BigDecimal("0.123456"))
                .setQualifiedQuantity(new BigDecimal("0.1"))
                .setUnqualifiedQuantity(new BigDecimal("0.023456")));
        MesProFeedbackDO saved = mapper.selectById(feedback.getId());
        assertEquals(0, new BigDecimal("0.123456").compareTo(saved.getFeedbackQuantity()));
        assertEquals(0, saved.getFeedbackQuantity()
                .compareTo(saved.getQualifiedQuantity().add(saved.getUnqualifiedQuantity())));
    }

    private MesProFeedbackDO insertFeedback(MesProFeedbackStatusEnum status) {
        MesProFeedbackDO feedback = new MesProFeedbackDO().setCode("FB-INTEGRITY").setWorkOrderId(3L)
                .setTaskId(2L).setRouteId(4L).setProcessId(5L).setWorkstationId(6L).setStatus(status.getStatus())
                .setFeedbackQuantity(BigDecimal.TEN).setQualifiedQuantity(new BigDecimal("8"))
                .setUnqualifiedQuantity(new BigDecimal("2")).setUncheckQuantity(BigDecimal.ZERO)
                .setLaborScrapQuantity(BigDecimal.ZERO).setMaterialScrapQuantity(BigDecimal.ZERO)
                .setOtherScrapQuantity(BigDecimal.ZERO);
        mapper.insert(feedback);
        return feedback;
    }
}
