package cn.iocoder.yudao.module.mes.service.pro.task;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopTaskSnapshotDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopStepRecordDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopTaskSnapshotMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopStepRecordMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProTaskStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import cn.iocoder.yudao.module.mes.service.pro.route.*;
import cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.MD_WORKSTATION_RESOURCE_UNAVAILABLE;

/** Boundary tests run real service mutations against controlled, locked snapshots. */
@ExtendWith(MockitoExtension.class)
class MesProTaskIntegrityTest {
    @InjectMocks private MesProTaskServiceImpl service;
    @Mock private MesProTaskMapper taskMapper;
    @Mock private MesProFeedbackMapper feedbackMapper;
    @Mock private MesSopTaskSnapshotMapper sopSnapshotMapper;
    @Mock private MesSopStepRecordMapper sopRecordMapper;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesMdItemService itemService;
    @Mock private MesMdWorkstationService workstationService;
    @Mock private MesProProcessService processService;
    @Mock private MesProRouteService routeService;
    @Mock private MesProRouteProductService routeProductService;
    @Mock private MesProRouteProcessService routeProcessService;
    @Mock private MesMdAutoCodeRecordService autoCodeRecordService;
    @Mock private MesProSopService sopService;
    @Mock private MesProductionIntegrityService integrityService;
    private final List<MesProTaskDO> tasks = new ArrayList<>();
    private MesProTaskDO existing;

    @BeforeEach
    void fixture() {
        existing = task(10L, 1L, 10L, "60");
        lenient().when(workOrderService.validateWorkOrderExistsForUpdate(anyLong())).thenAnswer(i ->
                new MesProWorkOrderDO().setId(i.getArgument(0)).setProductId(9L).setStatus(1).setQuantity(new BigDecimal("100")));
        lenient().when(itemService.validateItemExistsAndEnable(9L)).thenReturn(new MesMdItemDO().setId(9L).setName("Broky"));
        lenient().when(workstationService.validateWorkstationExistsAndEnable(anyLong())).thenAnswer(i ->
                new MesMdWorkstationDO().setId(i.getArgument(0)).setProcessId(i.getArgument(0)));
        lenient().when(routeService.validateRouteExists(8L)).thenReturn(new MesProRouteDO().setId(8L).setStatus(0));
        lenient().when(routeProductService.getRouteProductByItemId(9L)).thenReturn(new MesProRouteProductDO().setItemId(9L).setRouteId(8L));
        lenient().when(routeProcessService.getRouteProcessByRouteIdAndProcessId(eq(8L), anyLong())).thenReturn(new MesProRouteProcessDO());
        lenient().when(taskMapper.selectListByWorkOrderIdForUpdate(anyLong())).thenAnswer(i ->
                tasks.stream().filter(t -> t.getWorkOrderId().equals(i.<Long>getArgument(0))).toList());
        lenient().when(taskMapper.selectById(10L)).thenReturn(existing);
        lenient().when(taskMapper.selectByIdForUpdate(10L)).thenReturn(existing);
        lenient().when(taskMapper.insert(any(MesProTaskDO.class))).thenAnswer(i -> {
            MesProTaskDO created = i.getArgument(0);
            created.setId(99L);
            return 1;
        });
        lenient().when(autoCodeRecordService.generateAutoCode(anyString())).thenReturn("TASK-TEST");
    }

    @Test
    void stationSplitCannotOverAllocateAOneHundredUnitOrder() {
        tasks.add(existing);
        assertThrows(ServiceException.class, () -> service.createTask(request(1L, 10L, "41")));
        verify(taskMapper, never()).insert(any(MesProTaskDO.class));
        var locks = inOrder(integrityService, workOrderService, taskMapper);
        locks.verify(integrityService).lockBomGraph();
        locks.verify(workOrderService).validateWorkOrderExistsForUpdate(1L);
        locks.verify(taskMapper).selectListByWorkOrderIdForUpdate(1L);
    }

    @Test
    void createAndGanttUpdateRecheckPlannedResourceAvailabilityBeforeWriting() {
        doThrow(new ServiceException(MD_WORKSTATION_RESOURCE_UNAVAILABLE.getCode(), "计划班次人数不足"))
                .when(workstationService).validateWorkstationForSchedule(anyLong(), any());
        var create = request(1L, 10L, "1.000001");
        assertThrows(ServiceException.class, () -> service.createTask(create));
        var update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setStartTime(LocalDateTime.of(2026, 11, 2, 22, 0));
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        verify(taskMapper, never()).insert(any(MesProTaskDO.class));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
        verify(workstationService).validateWorkstationForSchedule(10L, update.getStartTime());
    }

    @Test
    void schedulePreviewReturnsResourceAndTimeEvidenceWithoutWritingOrReserving() {
        var request = request(1L, 10L, "1.000001");
        when(workOrderService.getWorkOrder(1L)).thenReturn(new MesProWorkOrderDO()
                .setId(1L).setProductId(9L).setStatus(1).setQuantity(new BigDecimal("100")));
        when(workstationService.getScheduleAvailability(10L, request.getStartTime())).thenReturn(
                new cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.MesMdWorkstationAvailabilityRespVO()
                        .setAvailable(false).setReasons(List.of("计划班次人数不足")));
        when(taskMapper.selectScheduleOverlaps(eq(10L), any(), any(), isNull())).thenReturn(List.of(
                new MesProTaskDO().setId(20L).setCode("TASK-OTHER").setStartTime(request.getStartTime())
                        .setEndTime(request.getStartTime().plusHours(8))));
        when(routeProcessService.getRouteProcessListByRouteId(8L)).thenReturn(List.of(
                new MesProRouteProcessDO().setProcessId(5L).setNextProcessId(10L).setLinkType(3)));
        when(taskMapper.selectListByWorkOrderId(1L)).thenReturn(List.of(
                new MesProTaskDO().setId(21L).setCode("TASK-PREVIOUS").setRouteId(8L).setProcessId(5L)
                        .setEndTime(request.getStartTime().plusHours(1)).setStatus(0)));
        var result = service.checkSchedule(request);
        assertFalse(result.getResourceAvailable());
        assertTrue(result.getBlockingReasons().contains("计划班次人数不足"));
        assertTrue(result.getWarnings().stream().anyMatch(s -> s.contains("TASK-OTHER")));
        assertEquals("TASK-PREVIOUS", result.getPredecessors().get(0).getCode());
        assertEquals(21L, result.getPredecessors().get(0).getId());
        verify(taskMapper, never()).insert(any(MesProTaskDO.class));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
        verifyNoInteractions(integrityService, autoCodeRecordService, sopService);
    }

    @Test
    void exactRemainderAndADifferentOperationRemainSchedulable() {
        tasks.add(existing);
        assertEquals(99L, service.createTask(request(1L, 10L, "40")));
        assertEquals(99L, service.createTask(request(1L, 20L, "100")));
        verify(taskMapper, times(2)).insert(any(MesProTaskDO.class));
    }

    @Test
    void cancelledAllocationIsReleasedButCompletedAllocationIsRetained() {
        tasks.add(existing.setStatus(MesProTaskStatusEnum.CANCELED.getStatus()));
        assertEquals(99L, service.createTask(request(1L, 10L, "100")));
        existing.setStatus(MesProTaskStatusEnum.FINISHED.getStatus());
        assertThrows(ServiceException.class, () -> service.createTask(request(1L, 10L, "41")));
    }

    @Test
    void updatingAnExistingSplitExcludesItselfFromTheSum() {
        tasks.add(existing);
        tasks.add(task(11L, 1L, 10L, "40"));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setQuantity(new BigDecimal("60"));
        assertDoesNotThrow(() -> service.updateTask(update));
        update.setQuantity(new BigDecimal("61"));
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        verify(taskMapper, times(1)).updateById(any(MesProTaskDO.class));
    }

    @Test
    void movingAnUnstartedTaskStillChecksDestinationCapacity() {
        tasks.add(existing);
        tasks.add(task(11L, 2L, 10L, "50"));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setWorkOrderId(2L);
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3, 4})
    void savedSubmittedPendingAndFinishedReportsAllProtectTaskRelationships(int status) {
        when(feedbackMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesProFeedbackDO().setTaskId(10L).setStatus(status)));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setWorkOrderId(2L);
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        assertThrows(ServiceException.class, () -> service.deleteTask(10L));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
        verify(taskMapper, never()).deleteById(anyLong());
    }

    @Test
    void reportHistoryPreventsWorkstationChangesAndQuantityReduction() {
        when(feedbackMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesProFeedbackDO().setTaskId(10L).setStatus(3)));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setWorkstationId(20L);
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        update.setWorkstationId(null);
        update.setQuantity(new BigDecimal("59"));
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
    }

    @Test
    void reportHistoryRetainsGanttTimeEditingWithoutReusingExecutionRelationships() {
        tasks.add(existing);
        when(feedbackMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesProFeedbackDO().setTaskId(10L).setStatus(3)));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setStartTime(LocalDateTime.of(2026, 10, 20, 8, 0));
        assertDoesNotThrow(() -> service.updateTask(update));
        ArgumentCaptor<MesProTaskDO> saved = ArgumentCaptor.forClass(MesProTaskDO.class);
        verify(taskMapper).updateById(saved.capture());
        assertEquals(update.getStartTime(), saved.getValue().getStartTime());
        assertEquals(update.getStartTime().plusHours(8), saved.getValue().getEndTime());
    }

    @Test
    void preexistingProductionProgressProtectsImportedTasksEvenWithoutReports() {
        existing.setProducedQuantity(BigDecimal.ONE);
        assertThrows(ServiceException.class, () -> service.deleteTask(10L));
        verify(taskMapper, never()).deleteById(anyLong());
    }

    @Test
    void confirmedSopEvidenceProtectsTaskBeforeAnyFeedbackExists() {
        tasks.add(existing);
        when(sopSnapshotMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesSopTaskSnapshotDO().setId(200L).setTaskId(10L)));
        when(sopRecordMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesSopStepRecordDO().setId(300L).setSnapshotId(200L)
                        .setConfirmedAt(LocalDateTime.of(2026, 10, 18, 9, 0))));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setWorkOrderId(2L);
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        update.setWorkOrderId(null);
        update.setQuantity(new BigDecimal("59"));
        assertThrows(ServiceException.class, () -> service.updateTask(update));
        assertThrows(ServiceException.class, () -> service.deleteTask(10L));
        verify(taskMapper, never()).updateById(any(MesProTaskDO.class));
        verify(taskMapper, never()).deleteById(anyLong());
    }

    @Test
    void confirmedSopEvidenceKeepsTimeOnlyEditingAvailable() {
        tasks.add(existing);
        when(sopSnapshotMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesSopTaskSnapshotDO().setId(200L).setTaskId(10L)));
        when(sopRecordMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesSopStepRecordDO().setId(300L).setSnapshotId(200L)
                        .setConfirmedAt(LocalDateTime.of(2026, 10, 18, 9, 0))));
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setStartTime(LocalDateTime.of(2026, 10, 20, 8, 0));
        assertDoesNotThrow(() -> service.updateTask(update));
        verify(taskMapper).updateById(any(MesProTaskDO.class));
    }

    @Test
    void merelyPinnedUnexecutedSopDoesNotPreventMovingOrDeletingUnstartedTask() {
        tasks.add(existing);
        when(sopSnapshotMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(new MesSopTaskSnapshotDO().setId(200L).setTaskId(10L)));
        // The confirmation-only query returns no evidence for an untouched or
        // merely viewed step; pinning a version is not proof of execution.
        when(sopRecordMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(10L);
        update.setWorkOrderId(2L);
        assertDoesNotThrow(() -> service.updateTask(update));
        assertDoesNotThrow(() -> service.deleteTask(10L));
        verify(taskMapper).updateById(any(MesProTaskDO.class));
        verify(taskMapper).deleteById(10L);
    }

    private MesProTaskSaveReqVO request(Long order, Long process, String quantity) {
        MesProTaskSaveReqVO req = new MesProTaskSaveReqVO();
        req.setWorkOrderId(order); req.setRouteId(8L); req.setProcessId(process); req.setWorkstationId(process);
        req.setItemId(9L); req.setQuantity(new BigDecimal(quantity));
        req.setStartTime(LocalDateTime.of(2026, 10, 18, 8, 0)); req.setDuration(1);
        return req;
    }

    private MesProTaskDO task(Long id, Long order, Long process, String quantity) {
        return new MesProTaskDO().setId(id).setWorkOrderId(order).setRouteId(8L).setProcessId(process)
                .setWorkstationId(process).setItemId(9L).setQuantity(new BigDecimal(quantity)).setStatus(0)
                .setStartTime(LocalDateTime.of(2026, 10, 18, 8, 0)).setDuration(1);
    }
}
