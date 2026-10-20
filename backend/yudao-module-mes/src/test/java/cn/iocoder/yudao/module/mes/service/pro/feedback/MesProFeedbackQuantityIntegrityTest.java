package cn.iocoder.yudao.module.mes.service.pro.feedback;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDO;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Boundary tests for quantity conservation before inventory and progress mutations. */
@ExtendWith(MockitoExtension.class)
class MesProFeedbackQuantityIntegrityTest {

    @Mock private MesProFeedbackFlowService flowService;

    @InjectMocks private MesProFeedbackServiceImpl service;
    @Mock private MesProFeedbackMapper mapper;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesProRouteProcessService routeProcessService;
    @Mock private MesMdWorkstationService workstationService;
    @Mock private MesProTaskService taskService;
    @Mock private MesWmItemConsumeService itemConsumeService;
    @Mock private MesWmProductProduceService productProduceService;
    @Mock private MesWmProductProduceLineService produceLineService;
    @Mock private MesProSopService sopService;

    @BeforeEach
    void prepareReferences() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser().setId(1L), null, List.of()));
        lenient().when(workstationService.validateWorkstationExists(6L))
                .thenReturn(new MesMdWorkstationDO().setId(6L).setProcessId(5L));
        lenient().when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(false).setCheckFlag(false));
        lenient().when(workOrderService.validateWorkOrderConfirmed(3L))
                .thenReturn(new MesProWorkOrderDO().setId(3L).setProductId(7L));
        lenient().when(taskService.validateTaskNotFinishedForUpdate(2L, 3L))
                .thenReturn(new MesProTaskDO().setId(2L).setWorkOrderId(3L).setWorkstationId(6L)
                        .setRouteId(4L).setProcessId(5L).setItemId(7L));
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsReportedOneWithHundredQualifiedBeforeSaving() {
        MesProFeedbackSaveReqVO req = request();
        req.setFeedbackQuantity(BigDecimal.ONE);
        req.setQualifiedQuantity(new BigDecimal("100"));
        req.setUnqualifiedQuantity(BigDecimal.ZERO);

        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH, () -> service.createFeedback(req));

        verify(mapper, never()).insert(any(MesProFeedbackDO.class));
        verifyNoInteractions(itemConsumeService, productProduceService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qualified", "unqualified", "pending", "labor", "material", "other"})
    void createRejectsEveryNegativeQuantityEvenWhenTheTotalWouldBalance(String field) {
        MesProFeedbackSaveReqVO req = request();
        switch (field) {
            case "qualified" -> { req.setQualifiedQuantity(new BigDecimal("-1")); req.setUnqualifiedQuantity(new BigDecimal("11")); }
            case "unqualified" -> { req.setQualifiedQuantity(new BigDecimal("11")); req.setUnqualifiedQuantity(new BigDecimal("-1")); }
            case "pending" -> req.setUncheckQuantity(new BigDecimal("-1"));
            case "labor" -> req.setLaborScrapQuantity(new BigDecimal("-1"));
            case "material" -> req.setMaterialScrapQuantity(new BigDecimal("-1"));
            case "other" -> req.setOtherScrapQuantity(new BigDecimal("-1"));
            default -> throw new AssertionError(field);
        }

        assertFailure(PRO_FEEDBACK_QUANTITY_NEGATIVE, () -> service.createFeedback(req));
        verify(mapper, never()).insert(any(MesProFeedbackDO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void createRejectsZeroOrNegativeTotal(String quantity) {
        MesProFeedbackSaveReqVO req = request();
        req.setFeedbackQuantity(new BigDecimal(quantity));
        assertFailure(PRO_FEEDBACK_QUANTITY_MUST_POSITIVE, () -> service.createFeedback(req));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0000001", "100000000000000"})
    void rejectsValuesThatWouldBeRoundedOrOverflowTheProductionDecimalColumns(String quantity) {
        MesProFeedbackSaveReqVO req = request();
        req.setFeedbackQuantity(new BigDecimal(quantity));
        req.setQualifiedQuantity(req.getFeedbackQuantity());
        req.setUnqualifiedQuantity(BigDecimal.ZERO);
        assertFailure(PRO_FEEDBACK_QUANTITY_PRECISION, () -> service.createFeedback(req));
    }

    @Test
    void exactDecimalFractionsAndDifferentScalesAreAcceptedAndMissingScrapsAreZeroed() {
        MesProFeedbackSaveReqVO req = request();
        req.setFeedbackQuantity(new BigDecimal("0.300000"));
        req.setQualifiedQuantity(new BigDecimal("0.1"));
        req.setUnqualifiedQuantity(new BigDecimal("0.20"));
        req.setUncheckQuantity(null);
        service.createFeedback(req);

        ArgumentCaptor<MesProFeedbackDO> saved = ArgumentCaptor.forClass(MesProFeedbackDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals(0, new BigDecimal("0.3").compareTo(saved.getValue().getFeedbackQuantity()));
        assertTrue(saved.getValue().getFlowRequired());
        assertFalse(saved.getValue().getFlowComplete());
        assertEquals(BigDecimal.ZERO, saved.getValue().getUncheckQuantity());
        assertEquals(BigDecimal.ZERO, saved.getValue().getLaborScrapQuantity());
        assertEquals(BigDecimal.ZERO, saved.getValue().getMaterialScrapQuantity());
        assertEquals(BigDecimal.ZERO, saved.getValue().getOtherScrapQuantity());
        var order = inOrder(workOrderService, taskService, mapper);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(3L);
        order.verify(taskService).validateTaskNotFinishedForUpdate(2L, 3L);
        order.verify(mapper).insert(any(MesProFeedbackDO.class));
    }

    @Test
    void scrapReasonsCannotExceedTheDefectiveQuantity() {
        MesProFeedbackSaveReqVO req = request();
        req.setLaborScrapQuantity(BigDecimal.ONE);
        req.setMaterialScrapQuantity(new BigDecimal("1.01"));
        assertFailure(PRO_FEEDBACK_SCRAP_QUANTITY_EXCEED, () -> service.createFeedback(req));
    }

    @Test
    void inspectionDraftDerivesItsPendingQuantityOnTheServer() {
        inspectionRoute();
        MesProFeedbackSaveReqVO req = request();
        req.setQualifiedQuantity(null);
        req.setUnqualifiedQuantity(null);
        req.setUncheckQuantity(null);

        service.createFeedback(req);

        ArgumentCaptor<MesProFeedbackDO> saved = ArgumentCaptor.forClass(MesProFeedbackDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals(0, BigDecimal.TEN.compareTo(saved.getValue().getUncheckQuantity()));
        assertEquals(BigDecimal.ZERO, saved.getValue().getQualifiedQuantity());
        assertEquals(BigDecimal.ZERO, saved.getValue().getUnqualifiedQuantity());
    }

    @Test
    void inspectionRejectsForgedClassifiedResultsAndAnExplicitlyWrongPendingQuantity() {
        inspectionRoute();
        MesProFeedbackSaveReqVO req = request();
        req.setUncheckQuantity(BigDecimal.TEN);
        assertFailure(PRO_FEEDBACK_PENDING_QUANTITY_MISMATCH, () -> service.createFeedback(req));
        req.setQualifiedQuantity(BigDecimal.ZERO);
        req.setUnqualifiedQuantity(BigDecimal.ZERO);
        req.setUncheckQuantity(BigDecimal.ONE);
        assertFailure(PRO_FEEDBACK_PENDING_QUANTITY_MISMATCH, () -> service.createFeedback(req));
        verify(mapper, never()).insert(any(MesProFeedbackDO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "submit", "approve"})
    void unsupportedNonKeyInspectionIsRejectedAtEveryReportBoundary(String boundary) {
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(false).setCheckFlag(true));
        MesProFeedbackSaveReqVO req = request();
        req.setQualifiedQuantity(BigDecimal.ZERO);
        req.setUnqualifiedQuantity(BigDecimal.ZERO);
        req.setUncheckQuantity(BigDecimal.TEN);
        if (!"create".equals(boundary)) {
            MesProFeedbackDO saved = savedFeedback("approve".equals(boundary)
                    ? MesProFeedbackStatusEnum.APPROVING : MesProFeedbackStatusEnum.PREPARE);
            saved.setQualifiedQuantity(BigDecimal.ZERO).setUnqualifiedQuantity(BigDecimal.ZERO)
                    .setUncheckQuantity(BigDecimal.TEN);
            req.setId(1L);
        }

        assertFailure(PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED, () -> {
            switch (boundary) {
                case "create" -> service.createFeedback(req);
                case "update" -> service.updateFeedback(req);
                case "submit" -> service.submitFeedback(1L);
                case "approve" -> service.approveFeedback(1L);
                default -> throw new AssertionError(boundary);
            }
        });

        verify(mapper, never()).insert(any(MesProFeedbackDO.class));
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
        verifyNoInteractions(itemConsumeService, productProduceService, produceLineService);
        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
    }

    @Test
    void updateCannotSaveInconsistentQuantities() {
        savedFeedback(MesProFeedbackStatusEnum.PREPARE);
        MesProFeedbackSaveReqVO req = request();
        req.setId(1L);
        req.setFeedbackQuantity(BigDecimal.ONE);
        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH, () -> service.updateFeedback(req));
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
    }

    @Test
    void legacyDraftCannotBeSubmittedWithAnInflatedQualifiedQuantity() {
        MesProFeedbackDO saved = savedFeedback(MesProFeedbackStatusEnum.PREPARE);
        saved.setFeedbackQuantity(BigDecimal.ONE).setQualifiedQuantity(new BigDecimal("100"))
                .setUnqualifiedQuantity(BigDecimal.ZERO);
        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH, () -> service.submitFeedback(1L));
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
        verifyNoInteractions(itemConsumeService, productProduceService);
    }

    @Test
    void legacyApprovingReportCannotInflateStockOrProgress() {
        MesProFeedbackDO saved = savedFeedback(MesProFeedbackStatusEnum.APPROVING);
        saved.setFeedbackQuantity(BigDecimal.ONE).setQualifiedQuantity(new BigDecimal("100"))
                .setUnqualifiedQuantity(BigDecimal.ZERO);
        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH, () -> service.approveFeedback(1L));
        verifyNoInteractions(itemConsumeService, productProduceService, produceLineService);
        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
    }

    @Test
    void missingRouteConfigurationCannotSilentlyApproveAsANonInspectionProcess() {
        savedFeedback(MesProFeedbackStatusEnum.APPROVING);
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L)).thenReturn(null);
        assertFailure(PRO_FEEDBACK_ROUTE_PROCESS_INVALID, () -> service.approveFeedback(1L));
        verifyNoInteractions(itemConsumeService, productProduceService);
    }

    @Test
    void outputRowsMustConserveTheReportedTotalBeforeUpdatingProgress() {
        savedFeedback(MesProFeedbackStatusEnum.APPROVING);
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(true).setCheckFlag(false));
        when(productProduceService.generateProductProduce(any(), eq(false)))
                .thenReturn(new MesWmProductProduceDO().setId(20L));
        when(produceLineService.getProductProduceLineListByFeedbackId(1L)).thenReturn(List.of(
                new MesWmProductProduceLineDO().setQuantity(new BigDecimal("100"))
                        .setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus())));

        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH, () -> service.approveFeedback(1L));

        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
        verify(workOrderService, never()).updateProducedQuantity(anyLong(), any());
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
    }

    @Test
    void inspectionCallbackRejectsWrongTotalsBeforeSplittingOutput() {
        savedFeedback(MesProFeedbackStatusEnum.UNCHECK);
        assertFailure(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH,
                () -> service.updateProFeedbackWhenIpqcFinish(1L, 30L, BigDecimal.TEN, BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        verifyNoInteractions(productProduceService, produceLineService);
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
        verify(taskService, never()).updateProducedQuantity(anyLong(), any(), any(), any());
    }

    @Test
    void inspectionCallbackRejectsNegativeQuantitiesEvenWhenTheirSumMatches() {
        savedFeedback(MesProFeedbackStatusEnum.UNCHECK);
        assertFailure(PRO_FEEDBACK_QUANTITY_NEGATIVE,
                () -> service.updateProFeedbackWhenIpqcFinish(1L, 30L, new BigDecimal("11"), new BigDecimal("-1"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        verifyNoInteractions(productProduceService, produceLineService);
    }

    @Test
    void updateAndDeleteRecheckTheLockedStatusInsteadOfTheInitialSnapshot() {
        savedFeedback(MesProFeedbackStatusEnum.PREPARE);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(new MesProFeedbackDO().setId(1L).setWorkOrderId(3L)
                .setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus()));
        MesProFeedbackSaveReqVO req = request();
        req.setId(1L);

        assertFailure(PRO_FEEDBACK_NOT_PREPARE, () -> service.updateFeedback(req));
        assertFailure(PRO_FEEDBACK_NOT_PREPARE, () -> service.deleteFeedback(1L));
        verify(mapper, never()).updateById(any(MesProFeedbackDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    private void inspectionRoute() {
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(4L, 5L))
                .thenReturn(new MesProRouteProcessDO().setKeyFlag(true).setCheckFlag(true));
    }

    private MesProFeedbackDO savedFeedback(MesProFeedbackStatusEnum status) {
        MesProFeedbackDO row = new MesProFeedbackDO().setId(1L).setWorkOrderId(3L).setTaskId(2L)
                .setWorkstationId(6L).setRouteId(4L).setProcessId(5L).setApproveUserId(1L)
                .setStatus(status.getStatus()).setFeedbackQuantity(BigDecimal.TEN)
                .setQualifiedQuantity(new BigDecimal("8")).setUnqualifiedQuantity(new BigDecimal("2"))
                .setUncheckQuantity(BigDecimal.ZERO);
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(row);
        return row;
    }

    private MesProFeedbackSaveReqVO request() {
        MesProFeedbackSaveReqVO req = new MesProFeedbackSaveReqVO();
        req.setWorkOrderId(3L);
        req.setTaskId(2L);
        req.setWorkstationId(6L);
        req.setRouteId(4L);
        req.setProcessId(5L);
        req.setItemId(7L);
        req.setFeedbackQuantity(BigDecimal.TEN);
        req.setQualifiedQuantity(new BigDecimal("8"));
        req.setUnqualifiedQuantity(new BigDecimal("2"));
        req.setUncheckQuantity(BigDecimal.ZERO);
        return req;
    }

    private void assertFailure(ErrorCode code, Runnable action) {
        ServiceException failure = assertThrows(ServiceException.class, action::run);
        assertEquals(code.getCode(), failure.getCode());
    }
}
