package cn.iocoder.yudao.module.mes.service.pro.task;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskScheduleCheckRespVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.unitmeasure.MesMdUnitMeasureDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProductDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopTaskSnapshotMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.MesSopStepRecordMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopTaskSnapshotDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopStepRecordDO;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.enums.md.autocode.MesMdAutoCodeRuleCodeEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProTaskStatusEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProWorkOrderStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.unitmeasure.MesMdUnitMeasureService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProductService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 生产任务 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesProTaskServiceImpl implements MesProTaskService {

    @Resource
    private MesProTaskMapper taskMapper;
    @Resource
    private MesProFeedbackMapper feedbackMapper;
    @Resource
    private MesSopTaskSnapshotMapper sopSnapshotMapper;
    @Resource
    private MesSopStepRecordMapper sopRecordMapper;

    @Resource
    @Lazy
    private MesProWorkOrderService workOrderService;
    @Resource
    private MesMdWorkstationService workstationService;
    @Resource
    private MesProProcessService processService;
    @Resource
    private MesMdItemService itemService;
    @Resource
    private MesMdUnitMeasureService unitMeasureService;
    @Resource
    private MesProRouteService routeService;
    @Resource
    private MesProRouteProductService routeProductService;
    @Resource
    private MesProRouteProcessService routeProcessService;
    @Resource
    private MesMdAutoCodeRecordService autoCodeRecordService;
    @Resource
    private MesProSopService sopService;
    @Resource
    private cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService integrityService;

    @Override
    public void validateFeedbackSequence(cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO feedback) {
        integrityService.validateFeedbackSequence(feedback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTask(MesProTaskSaveReqVO createReqVO) {
        integrityService.lockBomGraph();
        // 1. 锁定工单后验证整组排产数据，不能依赖页面的筛选条件。
        validateScheduleFields(createReqVO);
        MesProWorkOrderDO workOrder = workOrderService.validateWorkOrderExistsForUpdate(createReqVO.getWorkOrderId());
        MesMdItemDO item = validateScheduleRelations(createReqVO, workOrder);
        validateScheduledQuantity(createReqVO, workOrder, null, BigDecimal.ZERO);
        validateScheduleTime(createReqVO);
        workstationService.validateWorkstationForSchedule(createReqVO.getWorkstationId(), createReqVO.getStartTime());

        // 2.1 构建任务 DO：自动填充客户信息
        MesProTaskDO task = BeanUtils.toBean(createReqVO, MesProTaskDO.class)
                .setName(buildTaskName(item, createReqVO.getQuantity()))
                .setCode(autoCodeRecordService.generateAutoCode(MesMdAutoCodeRuleCodeEnum.PRO_TASK_CODE.getCode()))
                .setStatus(MesProTaskStatusEnum.PREPARE.getStatus());
        if (task.getClientId() == null && workOrder.getClientId() != null) {
            task.setClientId(workOrder.getClientId());
        }
        // 2.2 插入
        taskMapper.insert(task);
        sopService.lockForTask(task);
        updateWorkOrderScheduledQuantity(task.getWorkOrderId());
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTask(MesProTaskSaveReqVO updateReqVO) {
        integrityService.lockBomGraph();
        // 1.1 校验存在
        MesProTaskDO task = validateTaskNotFinished(updateReqVO.getId());
        Long workOrderId = updateReqVO.getWorkOrderId() != null ? updateReqVO.getWorkOrderId() : task.getWorkOrderId();
        lockWorkOrders(task.getWorkOrderId(), workOrderId);
        // 等锁前的对象只是定位工单的快照，不能用于后续状态、归属或数量判断。
        task = validateTaskNotFinishedForUpdate(task.getId(), task.getWorkOrderId());
        // 1.2 甘特图允许仅传时间字段，合并当前值后执行与新增相同的约束。
        MesProTaskSaveReqVO effective = BeanUtils.toBean(task, MesProTaskSaveReqVO.class);
        if (updateReqVO.getWorkOrderId() != null) effective.setWorkOrderId(updateReqVO.getWorkOrderId());
        if (updateReqVO.getWorkstationId() != null) effective.setWorkstationId(updateReqVO.getWorkstationId());
        if (updateReqVO.getRouteId() != null) effective.setRouteId(updateReqVO.getRouteId());
        if (updateReqVO.getProcessId() != null) effective.setProcessId(updateReqVO.getProcessId());
        if (updateReqVO.getItemId() != null) effective.setItemId(updateReqVO.getItemId());
        if (updateReqVO.getQuantity() != null) effective.setQuantity(updateReqVO.getQuantity());
        if (updateReqVO.getStartTime() != null) effective.setStartTime(updateReqVO.getStartTime());
        if (updateReqVO.getDuration() != null) effective.setDuration(updateReqVO.getDuration());
        // 起始时间/时长变化时重算结束时间；显式传结束时间则须一致。
        effective.setEndTime(updateReqVO.getEndTime());
        validateScheduleFields(effective);
        validateTaskHistory(task, effective, false);
        MesProWorkOrderDO workOrder = workOrderService.validateWorkOrderExistsForUpdate(workOrderId);
        MesMdItemDO item = validateScheduleRelations(effective, workOrder);
        validateScheduledQuantity(effective, workOrder, task.getId(), task.getChangedQuantity());
        validateScheduleTime(effective);
        workstationService.validateWorkstationForSchedule(effective.getWorkstationId(), effective.getStartTime());

        // 2. 更新
        MesProTaskDO updateObj = BeanUtils.toBean(updateReqVO, MesProTaskDO.class)
                .setName(buildTaskName(item, effective.getQuantity()))
                .setStartTime(effective.getStartTime()).setDuration(effective.getDuration())
                .setEndTime(effective.getEndTime());
        taskMapper.updateById(updateObj);
        sopService.lockForTask(taskMapper.selectById(task.getId()));
        updateWorkOrderScheduledQuantity(task.getWorkOrderId());
        if (!Objects.equals(task.getWorkOrderId(), workOrderId)) {
            updateWorkOrderScheduledQuantity(workOrderId);
        }
    }

    private void validateScheduleFields(MesProTaskSaveReqVO reqVO) {
        if (reqVO.getWorkOrderId() == null || reqVO.getWorkstationId() == null || reqVO.getRouteId() == null
                || reqVO.getProcessId() == null || reqVO.getItemId() == null || reqVO.getQuantity() == null
                || reqVO.getQuantity().signum() <= 0) {
            throw exception(PRO_TASK_SCHEDULE_INVALID);
        }
        BigDecimal quantity = reqVO.getQuantity().stripTrailingZeros();
        if (quantity.scale() > 6 || quantity.precision() - quantity.scale() > 14) {
            throw cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.invalid("排产数量最多十四位整数及六位小数");
        }
    }

    /** The locked work order serializes all station splits of the same operation. */
    private void validateScheduledQuantity(MesProTaskSaveReqVO req, MesProWorkOrderDO order,
                                            Long excludingTaskId, BigDecimal ownChangedQuantity) {
        BigDecimal capacity = zero(order.getQuantity()).add(zero(order.getQuantityChanged()));
        BigDecimal allocated = req.getQuantity().add(zero(ownChangedQuantity));
        for (MesProTaskDO other : taskMapper.selectListByWorkOrderIdForUpdate(order.getId())) {
            if (Objects.equals(other.getId(), excludingTaskId)
                    || Objects.equals(other.getStatus(), MesProTaskStatusEnum.CANCELED.getStatus())
                    || !Objects.equals(other.getRouteId(), req.getRouteId())
                    || !Objects.equals(other.getProcessId(), req.getProcessId())) continue;
            allocated = allocated.add(zero(other.getQuantity())).add(zero(other.getChangedQuantity()));
        }
        if (allocated.signum() <= 0 || allocated.compareTo(capacity) > 0) {
            throw cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.invalid("同一工单同工序的累计排产数量不能超过工单数量");
        }
    }

    /** Even saved draft reports must retain their original task relationships. */
    private void validateTaskHistory(MesProTaskDO task, MesProTaskSaveReqVO effective, boolean deleting) {
        boolean hasHistory = !feedbackMapper.selectList(new LambdaQueryWrapperX<MesProFeedbackDO>()
                .eq(MesProFeedbackDO::getTaskId, task.getId()).last("LIMIT 1 FOR UPDATE")).isEmpty();
        // Old imported tasks may carry progress even if their source reports are unavailable.
        hasHistory = hasHistory || zero(task.getProducedQuantity()).signum() > 0;
        if (!hasHistory) {
            // SOP confirmation holds the same work-order -> task locks. Current
            // reads see evidence committed while this mutation waited for them.
            var snapshots = sopSnapshotMapper.selectList(new LambdaQueryWrapperX<MesSopTaskSnapshotDO>()
                    .eq(MesSopTaskSnapshotDO::getTaskId, task.getId()).last("LIMIT 1 FOR UPDATE"));
            if (!snapshots.isEmpty()) {
                hasHistory = !sopRecordMapper.selectList(new LambdaQueryWrapperX<MesSopStepRecordDO>()
                        .eq(MesSopStepRecordDO::getSnapshotId, snapshots.get(0).getId())
                        .isNotNull(MesSopStepRecordDO::getConfirmedAt).last("LIMIT 1 FOR UPDATE")).isEmpty();
            }
        }
        if (!hasHistory) return;
        boolean relationshipsChanged = !deleting && (
                !Objects.equals(task.getWorkOrderId(), effective.getWorkOrderId())
                || !Objects.equals(task.getRouteId(), effective.getRouteId())
                || !Objects.equals(task.getProcessId(), effective.getProcessId())
                || !Objects.equals(task.getWorkstationId(), effective.getWorkstationId())
                || !Objects.equals(task.getItemId(), effective.getItemId()));
        if (deleting || relationshipsChanged || effective.getQuantity().compareTo(task.getQuantity()) < 0) {
            throw cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.invalid("任务已有报工或SOP执行记录，不能删除、更换执行关系或减少排产数量");
        }
    }

    private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private MesMdItemDO validateScheduleRelations(MesProTaskSaveReqVO reqVO, MesProWorkOrderDO workOrder) {
        if (!Objects.equals(workOrder.getStatus(), MesProWorkOrderStatusEnum.CONFIRMED.getStatus())) {
            throw exception(PRO_WORK_ORDER_NOT_CONFIRMED);
        }
        if (!Objects.equals(workOrder.getProductId(), reqVO.getItemId())) {
            throw exception(PRO_WORK_ORDER_PRODUCT_MISMATCH);
        }
        MesMdItemDO item = itemService.validateItemExistsAndEnable(reqVO.getItemId());
        MesMdWorkstationDO workstation = workstationService.validateWorkstationExistsAndEnable(reqVO.getWorkstationId());
        processService.validateProcessExistsAndEnable(reqVO.getProcessId());
        if (!Objects.equals(workstation.getProcessId(), reqVO.getProcessId())) {
            throw exception(PRO_WORKSTATION_PROCESS_MISMATCH);
        }
        MesProRouteDO route = routeService.validateRouteExists(reqVO.getRouteId());
        if (!CommonStatusEnum.isEnable(route.getStatus())) {
            throw exception(PRO_TASK_ROUTE_DISABLED);
        }
        MesProRouteProductDO routeProduct = routeProductService.getRouteProductByItemId(reqVO.getItemId());
        if (routeProduct == null || !Objects.equals(routeProduct.getRouteId(), reqVO.getRouteId())) {
            throw exception(PRO_TASK_ROUTE_PRODUCT_MISMATCH);
        }
        if (routeProcessService.getRouteProcessByRouteIdAndProcessId(reqVO.getRouteId(), reqVO.getProcessId()) == null) {
            throw exception(PRO_TASK_ROUTE_PROCESS_MISMATCH);
        }
        return item;
    }

    private void validateScheduleTime(MesProTaskSaveReqVO reqVO) {
        if (reqVO.getStartTime() == null || reqVO.getDuration() == null || reqVO.getDuration() <= 0) {
            throw exception(PRO_TASK_TIME_INVALID);
        }
        LocalDateTime endTime;
        try {
            endTime = reqVO.getStartTime().plusHours(reqVO.getDuration().longValue() * 8);
        } catch (java.time.DateTimeException ex) {
            throw exception(PRO_TASK_TIME_INVALID);
        }
        if (reqVO.getEndTime() != null && !reqVO.getEndTime().equals(endTime)) {
            throw exception(PRO_TASK_TIME_INVALID);
        }
        reqVO.setEndTime(endTime);
    }

    @Override
    public MesProTaskScheduleCheckRespVO checkSchedule(MesProTaskSaveReqVO request) {
        MesProTaskSaveReqVO effective = request;
        if (request.getId() != null) {
            MesProTaskDO task = validateTaskNotFinished(request.getId());
            effective = BeanUtils.toBean(task, MesProTaskSaveReqVO.class);
            if (request.getWorkOrderId() != null) effective.setWorkOrderId(request.getWorkOrderId());
            if (request.getWorkstationId() != null) effective.setWorkstationId(request.getWorkstationId());
            if (request.getRouteId() != null) effective.setRouteId(request.getRouteId());
            if (request.getProcessId() != null) effective.setProcessId(request.getProcessId());
            if (request.getItemId() != null) effective.setItemId(request.getItemId());
            if (request.getQuantity() != null) effective.setQuantity(request.getQuantity());
            if (request.getStartTime() != null) effective.setStartTime(request.getStartTime());
            if (request.getDuration() != null) effective.setDuration(request.getDuration());
            effective.setEndTime(request.getEndTime());
        }
        validateScheduleFields(effective);
        MesProWorkOrderDO order = workOrderService.getWorkOrder(effective.getWorkOrderId());
        if (order == null) throw exception(PRO_WORK_ORDER_NOT_EXISTS);
        validateScheduleRelations(effective, order);
        validateScheduleTime(effective);
        var availability = workstationService.getScheduleAvailability(effective.getWorkstationId(), effective.getStartTime());
        var result = new MesProTaskScheduleCheckRespVO().setWorkstationId(effective.getWorkstationId())
                .setStartTime(effective.getStartTime()).setEndTime(effective.getEndTime())
                .setResourceAvailable(availability.getAvailable()).setBlockingReasons(availability.getReasons())
                .setNotes(new java.util.ArrayList<>(availability.getNotes()));
        var overlaps = taskMapper.selectScheduleOverlaps(effective.getWorkstationId(), effective.getStartTime(),
                effective.getEndTime(), effective.getId());
        for (MesProTaskDO task : overlaps.stream().limit(100).toList()) {
            result.getWarnings().add("工作站与任务「" + task.getCode() + "」时间重叠（"
                    + task.getStartTime() + " 至 " + task.getEndTime() + "）");
        }
        if (overlaps.size() > 100) result.getWarnings().add("重叠任务超过100条，请缩小计划时段后核对");
        // Manual plans are advisory, as specified by the design. Execution and
        // feedback still enforce the actual preceding qualified capacity.
        var processes = routeProcessService.getRouteProcessListByRouteId(effective.getRouteId());
        for (var previous : processes) {
            if (!Objects.equals(previous.getNextProcessId(), effective.getProcessId())
                    || !Objects.equals(previous.getLinkType(), 3)) continue;
            for (MesProTaskDO task : taskMapper.selectListByWorkOrderId(effective.getWorkOrderId())) {
                if (Objects.equals(task.getRouteId(), effective.getRouteId())
                        && Objects.equals(task.getProcessId(), previous.getProcessId())
                        && !Objects.equals(task.getStatus(), MesProTaskStatusEnum.CANCELED.getStatus())
                        && task.getEndTime() != null) {
                    result.getPredecessors().add(new MesProTaskScheduleCheckRespVO.Predecessor()
                            .setId(task.getId()).setCode(task.getCode()).setEndTime(task.getEndTime()));
                }
            }
        }
        result.getNotes().add("时间冲突供人工确认，不自动移动其他任务；保存与实际作业会重新校验业务条件");
        return result;
    }

    /**
     * 构建任务名称，格式："产品名【数量】单位"
     */
    private String buildTaskName(MesMdItemDO item, BigDecimal quantity) {
        String unitName = "";
        if (item.getUnitMeasureId() != null) {
            MesMdUnitMeasureDO unit = unitMeasureService.getUnitMeasure(item.getUnitMeasureId());
            if (unit != null) {
                unitName = unit.getName();
            }
        }
        return item.getName() + "【" + quantity + "】" + unitName;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        integrityService.lockBomGraph();
        // 1. 校验存在且非终态
        MesProTaskDO task = validateTaskNotFinished(id);
        workOrderService.validateWorkOrderExistsForUpdate(task.getWorkOrderId());
        task = validateTaskNotFinishedForUpdate(id, task.getWorkOrderId());
        validateTaskHistory(task, null, true);

        // 2. 删除
        taskMapper.deleteById(id);
        updateWorkOrderScheduledQuantity(task.getWorkOrderId());
    }

    @Override
    public MesProTaskDO getTask(Long id) {
        return taskMapper.selectById(id);
    }

    @Override
    public PageResult<MesProTaskDO> getTaskPage(MesProTaskPageReqVO pageReqVO) {
        return taskMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesProTaskDO> getTaskListByWorkOrderIds(Collection<Long> workOrderIds) {
        if (CollUtil.isEmpty(workOrderIds)) {
            return Collections.emptyList();
        }
        return taskMapper.selectListByWorkOrderIds(workOrderIds);
    }

    @Override
    public MesProTaskDO validateTaskExists(Long id) {
        MesProTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(PRO_TASK_NOT_EXISTS);
        }
        return task;
    }

    @Override
    public MesProTaskDO validateTaskNotFinished(Long id) {
        MesProTaskDO task = validateTaskExists(id);
        if (MesProTaskStatusEnum.isEndStatus(task.getStatus())) {
            throw exception(PRO_TASK_ALREADY_FINISHED);
        }
        return task;
    }

    @Override
    public MesProTaskDO validateTaskExistsForUpdate(Long id, Long workOrderId) {
        MesProTaskDO task = taskMapper.selectByIdForUpdate(id);
        if (task == null) {
            throw exception(PRO_TASK_NOT_EXISTS);
        }
        if (!Objects.equals(task.getWorkOrderId(), workOrderId)) {
            throw exception(PRO_TASK_WORK_ORDER_MISMATCH);
        }
        return task;
    }

    @Override
    public MesProTaskDO validateTaskNotFinishedForUpdate(Long id, Long workOrderId) {
        MesProTaskDO task = validateTaskExistsForUpdate(id, workOrderId);
        if (MesProTaskStatusEnum.isEndStatus(task.getStatus())) {
            throw exception(PRO_TASK_ALREADY_FINISHED);
        }
        return task;
    }

    @Override
    public List<MesProTaskDO> getTaskList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return taskMapper.selectByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishTaskByOrderId(Long workOrderId) {
        workOrderService.validateWorkOrderExistsForUpdate(workOrderId);
        // 1. 读取工单下所有任务，过滤掉已完成/已取消的
        List<MesProTaskDO> tasks = taskMapper.selectListByWorkOrderIdForUpdate(workOrderId);
        LocalDateTime now = LocalDateTime.now();
        // 2. 构建待更新列表，批量更新
        List<MesProTaskDO> updateList = convertList(tasks,
                task -> new MesProTaskDO().setId(task.getId())
                        .setStatus(MesProTaskStatusEnum.FINISHED.getStatus()).setFinishDate(now),
                task -> !MesProTaskStatusEnum.isEndStatus(task.getStatus()));
        if (CollUtil.isNotEmpty(updateList)) {
            taskMapper.updateBatch(updateList);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTaskByOrderId(Long workOrderId) {
        workOrderService.validateWorkOrderExistsForUpdate(workOrderId);
        // 1. 读取工单下所有任务，过滤掉已完成/已取消的
        List<MesProTaskDO> tasks = taskMapper.selectListByWorkOrderIdForUpdate(workOrderId);
        LocalDateTime now = LocalDateTime.now();
        // 2. 构建待更新列表，批量更新
        List<MesProTaskDO> updateList = convertList(tasks,
                task -> new MesProTaskDO().setId(task.getId())
                        .setStatus(MesProTaskStatusEnum.CANCELED.getStatus()).setCancelDate(now),
                task -> !MesProTaskStatusEnum.isEndStatus(task.getStatus()));
        if (CollUtil.isNotEmpty(updateList)) {
            taskMapper.updateBatch(updateList);
        }
        updateWorkOrderScheduledQuantity(workOrderId);
    }

    private void lockWorkOrders(Long firstId, Long secondId) {
        // 跨工单移动任务时保持锁顺序，避免反向移动产生死锁。
        if (Objects.equals(firstId, secondId)) {
            workOrderService.validateWorkOrderExistsForUpdate(firstId);
        } else if (firstId < secondId) {
            workOrderService.validateWorkOrderExistsForUpdate(firstId);
            workOrderService.validateWorkOrderExistsForUpdate(secondId);
        } else {
            workOrderService.validateWorkOrderExistsForUpdate(secondId);
            workOrderService.validateWorkOrderExistsForUpdate(firstId);
        }
    }

    /**
     * 排产数量按同一路线/工序的任务求和，再取各组最大值。
     * 例如两道工序各排 50 件仍为 50 件；同工序分工作站排 20+30 件为 50 件。
     * 已完成任务保留历史排产，取消任务不占用排产数量。调用前须先锁工单。
     */
    private void updateWorkOrderScheduledQuantity(Long workOrderId) {
        Map<List<Long>, BigDecimal> processQuantities = new HashMap<>();
        // 使用当前读，避免事务中前面的校验查询建立快照后漏掉已提交的任务。
        for (MesProTaskDO task : taskMapper.selectListByWorkOrderIdForUpdate(workOrderId)) {
            if (Objects.equals(task.getStatus(), MesProTaskStatusEnum.CANCELED.getStatus())) {
                continue;
            }
            if (task.getQuantity() != null) {
                processQuantities.merge(Arrays.asList(task.getRouteId(), task.getProcessId()),
                        task.getQuantity(), BigDecimal::add);
            }
        }
        BigDecimal quantityScheduled = processQuantities.values().stream()
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        workOrderService.updateScheduledQuantity(workOrderId, quantityScheduled);
    }

    @Override
    public void updateProducedQuantity(Long id,
                                       BigDecimal incrProducedQuantity,
                                       BigDecimal incrQualifyQuantity,
                                       BigDecimal incrUnqualifyQuantity) {
        // 校验任务存在
        validateTaskExists(id);
        // 更新数量
        taskMapper.updateProducedQuantity(id, incrProducedQuantity, incrQualifyQuantity, incrUnqualifyQuantity);
    }

}
