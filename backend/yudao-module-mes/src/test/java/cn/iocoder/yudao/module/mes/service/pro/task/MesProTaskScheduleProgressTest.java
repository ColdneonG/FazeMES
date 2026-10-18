package cn.iocoder.yudao.module.mes.service.pro.task;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProductDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.enums.pro.MesProWorkOrderStatusEnum;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProductService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProTaskStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises task mutations against a tiny in-memory mapper, without Spring/DB. */
@ExtendWith(MockitoExtension.class)
class MesProTaskScheduleProgressTest {

    @InjectMocks private MesProTaskServiceImpl service;
    @Mock private MesProTaskMapper mapper;
    @Mock private MesProWorkOrderService workOrderService;
    @Mock private MesMdItemService itemService;
    @Mock private MesMdWorkstationService workstationService;
    @Mock private MesProProcessService processService;
    @Mock private MesProRouteService routeService;
    @Mock private MesProRouteProductService routeProductService;
    @Mock private MesProRouteProcessService routeProcessService;
    @Mock private MesMdAutoCodeRecordService autoCodeRecordService;
    @Mock private cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService sopService;
    @Mock private cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService integrityService;
    @Mock private cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper feedbackMapper;
    @Mock private cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopTaskSnapshotMapper sopSnapshotMapper;
    @Mock private cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopStepRecordMapper sopRecordMapper;

    private final Map<Long, MesProTaskDO> tasks = new LinkedHashMap<>();
    private final Map<Long, BigDecimal> scheduled = new HashMap<>();
    private long nextId = 100L;

    @BeforeEach
    void mapperState() {
        lenient().when(workOrderService.validateWorkOrderExistsForUpdate(anyLong()))
                .thenAnswer(i -> new MesProWorkOrderDO().setId(i.getArgument(0)).setProductId(9L)
                        .setQuantity(new BigDecimal("1000")).setStatus(MesProWorkOrderStatusEnum.CONFIRMED.getStatus()));
        lenient().doAnswer(i -> { scheduled.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(workOrderService).updateScheduledQuantity(anyLong(), any());
        lenient().when(itemService.validateItemExistsAndEnable(anyLong()))
                .thenReturn(new MesMdItemDO().setId(9L).setName("Demo product"));
        lenient().when(workstationService.validateWorkstationExistsAndEnable(anyLong()))
                .thenAnswer(i -> new MesMdWorkstationDO().setId(i.getArgument(0)).setProcessId(i.getArgument(0)));
        lenient().when(routeService.validateRouteExists(8L))
                .thenReturn(new MesProRouteDO().setId(8L).setStatus(CommonStatusEnum.ENABLE.getStatus()));
        lenient().when(routeProductService.getRouteProductByItemId(9L))
                .thenReturn(new MesProRouteProductDO().setRouteId(8L).setItemId(9L));
        lenient().when(routeProcessService.getRouteProcessByRouteIdAndProcessId(eq(8L), anyLong()))
                .thenReturn(new MesProRouteProcessDO());
        lenient().when(autoCodeRecordService.generateAutoCode(anyString())).thenReturn("DEMO-TASK");
        lenient().when(mapper.selectById(anyLong())).thenAnswer(i -> copy(tasks.get(i.<Long>getArgument(0))));
        lenient().when(mapper.selectByIdForUpdate(anyLong())).thenAnswer(i -> copy(tasks.get(i.<Long>getArgument(0))));
        lenient().when(mapper.selectListByWorkOrderIdForUpdate(anyLong())).thenAnswer(i -> taskList(i.getArgument(0)));
        lenient().doAnswer(i -> {
            MesProTaskDO task = i.getArgument(0);
            task.setId(nextId++);
            tasks.put(task.getId(), copy(task));
            return 1;
        }).when(mapper).insert(any(MesProTaskDO.class));
        lenient().doAnswer(i -> { merge(i.getArgument(0)); return 1; })
                .when(mapper).updateById(any(MesProTaskDO.class));
        lenient().doAnswer(i -> { tasks.remove(i.<Long>getArgument(0)); return 1; })
                .when(mapper).deleteById(anyLong());
        lenient().doAnswer(i -> {
            for (MesProTaskDO task : i.<Collection<MesProTaskDO>>getArgument(0)) {
                merge(task);
            }
            return true;
        }).when(mapper).updateBatch(anyCollection());
    }

    @Test
    void twoProcessesOfFiftyCountAsFiftyProducts() {
        create(1L, 10L, "50");
        create(1L, 20L, "50");

        assertQuantity(1L, "50");
        var order = inOrder(workOrderService, mapper);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(1L);
        order.verify(mapper).insert(any(MesProTaskDO.class));
    }

    @Test
    void sameProcessSplitBetweenWorkstationsAddsItsQuantities() {
        create(1L, 10L, "20");
        create(1L, 10L, "30");

        assertQuantity(1L, "50");
    }

    @Test
    void resizingAndDeletingTasksRecalculatesRemainingAllocation() {
        Long firstId = create(1L, 10L, "20");
        Long secondId = create(1L, 10L, "30");
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(secondId);
        update.setQuantity(new BigDecimal("15"));
        service.updateTask(update);
        assertQuantity(1L, "35");

        service.deleteTask(firstId);
        assertQuantity(1L, "15");
        service.deleteTask(secondId);
        assertQuantity(1L, "0");
    }

    @Test
    void cancellingAllActiveTasksReleasesTheirAllocation() {
        create(1L, 10L, "50");
        create(1L, 20L, "50");

        service.cancelTaskByOrderId(1L);

        assertQuantity(1L, "0");
        assertTrue(tasks.values().stream().allMatch(t -> t.getStatus().equals(MesProTaskStatusEnum.CANCELED.getStatus())));
    }

    @Test
    void cancellationRetainsFinishedTaskHistoryAndIgnoresAlreadyCancelledTasks() {
        Long finished = create(1L, 10L, "30");
        Long cancelled = create(1L, 10L, "200");
        create(1L, 20L, "50");
        tasks.get(finished).setStatus(MesProTaskStatusEnum.FINISHED.getStatus());
        tasks.get(cancelled).setStatus(MesProTaskStatusEnum.CANCELED.getStatus());

        service.cancelTaskByOrderId(1L);

        assertQuantity(1L, "30");
        assertEquals(MesProTaskStatusEnum.FINISHED.getStatus(), tasks.get(finished).getStatus());
    }

    @Test
    void movingTaskToAnotherWorkOrderRecalculatesBothSidesAndOrdersLocks() {
        Long movedId = create(3L, 10L, "30");
        create(1L, 10L, "20");
        clearInvocations(workOrderService, mapper);
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(movedId);
        update.setWorkOrderId(1L);

        service.updateTask(update);

        assertQuantity(3L, "0");
        assertQuantity(1L, "50");
        var order = inOrder(workOrderService, mapper);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(1L);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(3L);
        order.verify(mapper).selectByIdForUpdate(movedId);
        order.verify(mapper).updateById(any(MesProTaskDO.class));
    }

    @Test
    void updateRejectsTaskMovedDuringWorkOrderLockWait() {
        Long id = create(1L, 10L, "30");
        clearInvocations(mapper, workOrderService);
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenAnswer(i -> {
            tasks.get(id).setWorkOrderId(2L);
            return new MesProWorkOrderDO().setId(1L);
        });
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(id);
        update.setQuantity(new BigDecimal("15"));

        assertThrows(RuntimeException.class, () -> service.updateTask(update));

        assertEquals(new BigDecimal("30"), tasks.get(id).getQuantity());
        verify(mapper, never()).updateById(any(MesProTaskDO.class));
        verify(workOrderService, never()).updateScheduledQuantity(anyLong(), any());
    }

    @Test
    void deleteRejectsTaskMovedDuringWorkOrderLockWait() {
        Long id = create(1L, 10L, "30");
        clearInvocations(mapper, workOrderService);
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenAnswer(i -> {
            tasks.get(id).setWorkOrderId(2L);
            return new MesProWorkOrderDO().setId(1L);
        });

        assertThrows(RuntimeException.class, () -> service.deleteTask(id));

        assertNotNull(tasks.get(id));
        verify(mapper, never()).deleteById(anyLong());
        verify(workOrderService, never()).updateScheduledQuantity(anyLong(), any());
    }

    @Test
    void updateAndDeleteRejectTasksFinishedWhileWaitingForWorkOrderLock() {
        Long id = create(1L, 10L, "30");
        clearInvocations(mapper, workOrderService);
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenAnswer(i -> {
            tasks.get(id).setStatus(MesProTaskStatusEnum.FINISHED.getStatus());
            return new MesProWorkOrderDO().setId(1L);
        });
        MesProTaskSaveReqVO update = new MesProTaskSaveReqVO();
        update.setId(id);
        update.setQuantity(new BigDecimal("15"));

        assertThrows(RuntimeException.class, () -> service.updateTask(update));
        tasks.get(id).setStatus(MesProTaskStatusEnum.PREPARE.getStatus());
        assertThrows(RuntimeException.class, () -> service.deleteTask(id));

        verify(mapper, never()).updateById(any(MesProTaskDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void cancelUsesCurrentTasksAfterLockAndDoesNotTouchMovedOrFinishedTasks() {
        Long moved = create(1L, 10L, "20");
        Long finished = create(1L, 10L, "30");
        clearInvocations(mapper, workOrderService);
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenAnswer(i -> {
            tasks.get(moved).setWorkOrderId(2L);
            tasks.get(finished).setStatus(MesProTaskStatusEnum.FINISHED.getStatus());
            return new MesProWorkOrderDO().setId(1L);
        });

        service.cancelTaskByOrderId(1L);

        assertEquals(MesProTaskStatusEnum.PREPARE.getStatus(), tasks.get(moved).getStatus());
        assertEquals(MesProTaskStatusEnum.FINISHED.getStatus(), tasks.get(finished).getStatus());
        assertQuantity(1L, "30");
        var order = inOrder(workOrderService, mapper);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(1L);
        order.verify(mapper, times(2)).selectListByWorkOrderIdForUpdate(1L);
        verify(mapper, never()).selectListByWorkOrderId(anyLong());
    }

    @Test
    void finishingTasksLocksWorkOrderBeforeReadingAndUpdatingTasks() {
        create(1L, 10L, "50");
        clearInvocations(mapper, workOrderService);

        service.finishTaskByOrderId(1L);

        var order = inOrder(workOrderService, mapper);
        order.verify(workOrderService).validateWorkOrderExistsForUpdate(1L);
        order.verify(mapper).selectListByWorkOrderIdForUpdate(1L);
        order.verify(mapper).updateBatch(anyCollection());
        assertTrue(tasks.values().stream().allMatch(t -> t.getStatus().equals(MesProTaskStatusEnum.FINISHED.getStatus())));
    }

    @Test
    void currentReadForInspectionAllowsFinishedTasksButMutationCheckRejectsThem() {
        Long id = create(1L, 10L, "50");
        tasks.get(id).setStatus(MesProTaskStatusEnum.FINISHED.getStatus());

        assertEquals(MesProTaskStatusEnum.FINISHED.getStatus(), service.validateTaskExistsForUpdate(id, 1L).getStatus());
        assertThrows(RuntimeException.class, () -> service.validateTaskNotFinishedForUpdate(id, 1L));
        assertThrows(RuntimeException.class, () -> service.validateTaskExistsForUpdate(id, 2L));
    }

    @Test
    void createRejectsDraftWorkOrderBeforeInsertingTask() {
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenReturn(
                new MesProWorkOrderDO().setId(1L).setProductId(9L).setStatus(MesProWorkOrderStatusEnum.PREPARE.getStatus()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        verify(mapper, never()).insert(any(MesProTaskDO.class));
    }

    @Test
    void futureSchedulingDoesNotDependOnTheCurrentShiftAvailability() {
        lenient().when(workstationService.validateWorkstationAvailable(anyLong())).thenThrow(
                new cn.iocoder.yudao.framework.common.exception.ServiceException(
                        cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.MD_WORKSTATION_RESOURCE_UNAVAILABLE));

        Long id = create(1L, 10L, "20");

        assertEquals(LocalDateTime.of(2026, 10, 18, 8, 0), tasks.get(id).getStartTime());
        assertQuantity(1L, "20");
        verify(workstationService).validateWorkstationExistsAndEnable(10L);
        verify(workstationService, never()).validateWorkstationAvailable(anyLong());
    }

    @Test
    void createRejectsWorkstationOfAnotherProcess() {
        when(workstationService.validateWorkstationExistsAndEnable(10L))
                .thenReturn(new MesMdWorkstationDO().setId(10L).setProcessId(20L));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        verify(mapper, never()).insert(any(MesProTaskDO.class));
    }

    @Test
    void createRejectsDisabledRouteAndProductAssignedToAnotherRoute() {
        MesProRouteDO route = new MesProRouteDO().setId(8L).setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(routeService.validateRouteExists(8L)).thenReturn(route);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        route.setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(routeProductService.getRouteProductByItemId(9L)).thenReturn(new MesProRouteProductDO().setRouteId(99L));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        verify(mapper, never()).insert(any(MesProTaskDO.class));
    }

    @Test
    void createRejectsProcessOutsideRouteAndProductOutsideWorkOrder() {
        when(routeProcessService.getRouteProcessByRouteIdAndProcessId(8L, 10L)).thenReturn(null);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenReturn(
                new MesProWorkOrderDO().setId(1L).setProductId(99L).setStatus(MesProWorkOrderStatusEnum.CONFIRMED.getStatus()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> create(1L, 10L, "20"));
        verify(mapper, never()).insert(any(MesProTaskDO.class));
    }

    @Test
    void ganttTimeOnlyPatchPreservesAllocationAndUsesEightHourWorkdays() {
        Long id = create(1L, 10L, "20");
        MesProTaskSaveReqVO change = new MesProTaskSaveReqVO();
        change.setId(id);
        change.setStartTime(LocalDateTime.of(2026, 10, 19, 8, 0));
        change.setDuration(2);
        change.setEndTime(change.getStartTime().plusHours(16));

        service.updateTask(change);

        assertEquals(change.getStartTime(), tasks.get(id).getStartTime());
        assertEquals(change.getEndTime(), tasks.get(id).getEndTime());
        assertEquals(2, tasks.get(id).getDuration());
        assertEquals(new BigDecimal("20"), tasks.get(id).getQuantity());
        assertQuantity(1L, "20");
    }

    @Test
    void ganttRejectsInvertedOrInconsistentTimesAndClosedOrder() {
        Long id = create(1L, 10L, "20");
        clearInvocations(mapper);
        MesProTaskSaveReqVO change = new MesProTaskSaveReqVO();
        change.setId(id);
        change.setStartTime(LocalDateTime.of(2026, 10, 19, 8, 0));
        change.setDuration(1);
        change.setEndTime(change.getStartTime().minusHours(8));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.updateTask(change));
        change.setEndTime(change.getStartTime().plusHours(16));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.updateTask(change));
        change.setEndTime(change.getStartTime().plusHours(8));
        when(workOrderService.validateWorkOrderExistsForUpdate(1L)).thenReturn(
                new MesProWorkOrderDO().setId(1L).setProductId(9L).setStatus(MesProWorkOrderStatusEnum.FINISHED.getStatus()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.updateTask(change));
        verify(mapper, never()).updateById(any(MesProTaskDO.class));
    }

    private Long create(Long workOrderId, Long processId, String quantity) {
        MesProTaskSaveReqVO req = new MesProTaskSaveReqVO();
        req.setWorkOrderId(workOrderId);
        req.setRouteId(8L);
        req.setProcessId(processId);
        req.setWorkstationId(processId);
        req.setItemId(9L);
        req.setQuantity(new BigDecimal(quantity));
        req.setStartTime(LocalDateTime.of(2026, 10, 18, 8, 0));
        req.setDuration(1);
        return service.createTask(req);
    }

    private void assertQuantity(Long workOrderId, String value) {
        assertNotNull(scheduled.get(workOrderId));
        assertEquals(0, new BigDecimal(value).compareTo(scheduled.get(workOrderId)));
    }

    private List<MesProTaskDO> taskList(Long workOrderId) {
        return tasks.values().stream().filter(t -> workOrderId.equals(t.getWorkOrderId())).map(this::copy).toList();
    }

    private MesProTaskDO copy(MesProTaskDO task) {
        if (task == null) return null;
        return cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(task, MesProTaskDO.class);
    }

    private void merge(MesProTaskDO update) {
        MesProTaskDO task = tasks.get(update.getId());
        if (update.getWorkOrderId() != null) task.setWorkOrderId(update.getWorkOrderId());
        if (update.getRouteId() != null) task.setRouteId(update.getRouteId());
        if (update.getProcessId() != null) task.setProcessId(update.getProcessId());
        if (update.getQuantity() != null) task.setQuantity(update.getQuantity());
        if (update.getStartTime() != null) task.setStartTime(update.getStartTime());
        if (update.getEndTime() != null) task.setEndTime(update.getEndTime());
        if (update.getDuration() != null) task.setDuration(update.getDuration());
        if (update.getStatus() != null) task.setStatus(update.getStatus());
    }
}
