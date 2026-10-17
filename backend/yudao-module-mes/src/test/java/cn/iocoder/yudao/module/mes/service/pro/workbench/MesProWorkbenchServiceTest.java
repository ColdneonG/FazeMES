package cn.iocoder.yudao.module.mes.service.pro.workbench;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.process.MesProProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.enums.pro.*;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.*;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.feedback.MesProFeedbackService;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesProWorkbenchServiceTest {
    @Spy @InjectMocks private MesProWorkbenchServiceImpl service;
    @Mock private MesProTaskService taskService;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesMdWorkstationService workstationService;
    @Mock private MesMdItemService itemService;
    @Mock private MesMdProductSopService sopService;
    @Mock private MesProProcessService processService;
    @Mock private MesProRouteProcessService routeProcessService;
    @Mock private MesProFeedbackService feedbackService;
    @Mock private MesMdAutoCodeRecordService autoCodeService;
    @Mock private AdminUserApi userApi;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void stationTasksAreChronologicalAndExcludeClosedWorkOrders() {
        MesMdWorkstationDO station = new MesMdWorkstationDO().setId(1L).setName("示例工作站");
        when(workstationService.validateWorkstationExistsAndEnable(1L)).thenReturn(station);
        MesProTaskDO late = task(2L, 10L).setStartTime(LocalDateTime.of(2026, 10, 20, 8, 0));
        MesProTaskDO early = task(1L, 10L).setStartTime(LocalDateTime.of(2026, 10, 18, 8, 0));
        MesProTaskDO closed = task(3L, 20L).setStartTime(LocalDateTime.of(2026, 10, 17, 8, 0));
        when(taskService.getTaskPage(any())).thenReturn(new PageResult<>(List.of(late, closed, early), 3L));
        when(workOrderService.getWorkOrderMap(anyCollection())).thenReturn(Map.of(
                10L, new MesProWorkOrderDO().setId(10L).setStatus(MesProWorkOrderStatusEnum.CONFIRMED.getStatus()),
                20L, new MesProWorkOrderDO().setId(20L).setStatus(MesProWorkOrderStatusEnum.FINISHED.getStatus())));
        when(itemService.getItemMap(anyCollection())).thenReturn(Map.of(9L, new MesMdItemDO().setId(9L).setName("示例产品")));
        when(processService.getProcessMap(anyCollection())).thenReturn(Map.of(5L, new MesProProcessDO().setId(5L).setName("示例工序")));

        var results = service.getTasks(1L);

        assertEquals(List.of(1L, 2L), results.stream().map(MesProWorkbenchTaskRespVO::getId).toList());
        verify(taskService).getTaskPage(argThat(query -> query.getWorkstationId().equals(1L)
                && query.getStatus().equals(MesProTaskStatusEnum.PREPARE.getStatus())));
        verify(workstationService, never()).validateWorkstationAvailable(anyLong());
    }

    @Test
    void contextOnlyReturnsSopsOfTheTaskProductAndProcess() {
        when(taskService.validateTaskNotFinished(7L)).thenReturn(task(7L, 10L));
        when(workstationService.validateWorkstationExistsAndEnable(1L)).thenReturn(new MesMdWorkstationDO().setId(1L).setProcessId(5L));
        when(workOrderService.validateWorkOrderConfirmed(10L)).thenReturn(new MesProWorkOrderDO().setId(10L).setCode("WO-10"));
        when(itemService.validateItemExistsAndEnable(9L)).thenReturn(new MesMdItemDO().setId(9L).setName("示例产品"));
        when(processService.getProcess(5L)).thenReturn(new MesProProcessDO().setId(5L));
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(8L, 5L)).thenReturn(new MesProRouteProcessDO().setCheckFlag(false));
        when(sopService.getProductSopListByItemId(9L)).thenReturn(List.of(
                new MesMdProductSopDO().setId(1L).setProcessId(5L).setSort(2).setTitle("步骤二"),
                new MesMdProductSopDO().setId(2L).setProcessId(99L).setSort(1).setTitle("其他工序"),
                new MesMdProductSopDO().setId(3L).setProcessId(5L).setSort(1).setTitle("步骤一")));
        when(userApi.getUserListByStatus(0)).thenReturn(List.of(new AdminUserRespDTO().setId(2L).setNickname("审核人")));

        var result = service.getContext(7L);

        assertEquals(List.of(3L, 1L), result.getSops().stream().map(sop -> sop.getId()).toList());
        assertEquals("WO-10", result.getTask().getWorkOrderCode());
        assertFalse(result.getTask().getCheckFlag());
        verify(workstationService, never()).validateWorkstationAvailable(anyLong());
    }

    @Test
    void submissionDerivesAssociationAndOperatorAndLeavesApprovalPending() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(42L), null, List.of()));
        var task = new MesProWorkbenchTaskRespVO().setId(7L).setWorkstationId(1L).setWorkOrderId(10L)
                .setRouteId(8L).setProcessId(5L).setItemId(9L).setQuantity(BigDecimal.TEN).setCheckFlag(false);
        doReturn(new MesProWorkbenchContextRespVO().setTask(task)).when(service).getContext(7L);
        when(autoCodeService.generateAutoCode(anyString())).thenReturn("FB-1");
        when(feedbackService.createFeedback(any())).thenReturn(100L);
        var req = new MesProWorkbenchFeedbackReqVO().setTaskId(7L).setApproveUserId(2L)
                .setQualifiedQuantity(new BigDecimal("8")).setUnqualifiedQuantity(new BigDecimal("2"));

        assertEquals(100L, service.submitFeedback(req));

        ArgumentCaptor<MesProFeedbackSaveReqVO> saved = ArgumentCaptor.forClass(MesProFeedbackSaveReqVO.class);
        verify(feedbackService).createFeedback(saved.capture());
        assertEquals(42L, saved.getValue().getFeedbackUserId());
        assertEquals(MesProFeedbackTypeEnum.SELF.getType(), saved.getValue().getType());
        assertEquals(10L, saved.getValue().getWorkOrderId());
        assertEquals(9L, saved.getValue().getItemId());
        assertEquals(0, BigDecimal.TEN.compareTo(saved.getValue().getFeedbackQuantity()));
        verify(feedbackService).submitFeedback(100L);
        verify(feedbackService, never()).approveFeedback(anyLong());
    }

    @Test
    void unauthenticatedServiceInvocationCannotImpersonateOperator() {
        assertThrows(ServiceException.class, () -> service.submitFeedback(new MesProWorkbenchFeedbackReqVO().setTaskId(7L)));
        verifyNoInteractions(feedbackService, taskService);
    }

    private MesProTaskDO task(Long id, Long orderId) {
        return new MesProTaskDO().setId(id).setWorkstationId(1L).setWorkOrderId(orderId)
                .setItemId(9L).setRouteId(8L).setProcessId(5L).setQuantity(BigDecimal.TEN);
    }
}
