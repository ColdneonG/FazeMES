package cn.iocoder.yudao.module.mes.service.pro.workbench;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.sop.MesMdProductSopRespVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.process.MesProProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.enums.md.autocode.MesMdAutoCodeRuleCodeEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackTypeEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProTaskStatusEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProWorkOrderStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdProductSopService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.feedback.MesProFeedbackService;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

@Service
public class MesProWorkbenchServiceImpl implements MesProWorkbenchService {
    @Resource private MesProTaskService taskService;
    @Resource private MesProWorkOrderService workOrderService;
    @Resource private MesMdWorkstationService workstationService;
    @Resource private MesMdItemService itemService;
    @Resource private MesMdProductSopService sopService;
    @Resource private MesProProcessService processService;
    @Resource private MesProRouteProcessService routeProcessService;
    @Resource private MesProFeedbackService feedbackService;
    @Resource private MesMdAutoCodeRecordService autoCodeService;
    @Resource private AdminUserApi userApi;

    @Override
    public List<MesProWorkbenchTaskRespVO> getTasks(Long workstationId) {
        MesMdWorkstationDO workstation = workstationService.validateWorkstationExistsAndEnable(workstationId);
        MesProTaskPageReqVO query = new MesProTaskPageReqVO();
        query.setWorkstationId(workstationId);
        query.setStatus(MesProTaskStatusEnum.PREPARE.getStatus());
        query.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<MesProTaskDO> tasks = taskService.getTaskPage(query).getList();
        Map<Long, MesProWorkOrderDO> orders = workOrderService.getWorkOrderMap(convertSet(tasks, MesProTaskDO::getWorkOrderId));
        Map<Long, MesMdItemDO> items = itemService.getItemMap(convertSet(tasks, MesProTaskDO::getItemId));
        Map<Long, MesProProcessDO> processes = processService.getProcessMap(convertSet(tasks, MesProTaskDO::getProcessId));
        return tasks.stream().filter(task -> orders.containsKey(task.getWorkOrderId())
                        && MesProWorkOrderStatusEnum.CONFIRMED.getStatus().equals(orders.get(task.getWorkOrderId()).getStatus()))
                .sorted(Comparator.comparing(MesProTaskDO::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MesProTaskDO::getId))
                .map(task -> buildTask(task, workstation, orders.get(task.getWorkOrderId()),
                        items.get(task.getItemId()), processes.get(task.getProcessId())))
                .toList();
    }

    @Override
    public MesProWorkbenchContextRespVO getContext(Long taskId) {
        MesProTaskDO task = taskService.validateTaskNotFinished(taskId);
        MesMdWorkstationDO workstation = workstationService.validateWorkstationExistsAndEnable(task.getWorkstationId());
        MesProWorkOrderDO order = workOrderService.validateWorkOrderConfirmed(task.getWorkOrderId());
        MesMdItemDO item = itemService.validateItemExistsAndEnable(task.getItemId());
        processService.validateProcessExistsAndEnable(task.getProcessId());
        MesProProcessDO process = processService.getProcess(task.getProcessId());
        if (!Objects.equals(workstation.getProcessId(), task.getProcessId())) throw exception(PRO_WORKSTATION_PROCESS_MISMATCH);
        MesProRouteProcessDO routeProcess = routeProcessService.getRouteProcessByRouteIdAndProcessId(task.getRouteId(), task.getProcessId());
        if (routeProcess == null) throw exception(PRO_FEEDBACK_ROUTE_PROCESS_INVALID);
        MesProWorkbenchTaskRespVO taskView = buildTask(task, workstation, order, item, process);
        taskView.setCheckFlag(Boolean.TRUE.equals(routeProcess.getCheckFlag()));
        return new MesProWorkbenchContextRespVO().setTask(taskView)
                .setSops(sopService.getProductSopListByItemId(task.getItemId()).stream()
                        .filter(sop -> Objects.equals(sop.getProcessId(), task.getProcessId()))
                        .sorted(Comparator.comparing(sop -> sop.getSort() == null ? 0 : sop.getSort()))
                        .map(sop -> BeanUtils.toBean(sop, MesMdProductSopRespVO.class)).toList())
                .setApprovers(userApi.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus()).stream()
                        .map(user -> new MesProWorkbenchContextRespVO.UserOption().setId(user.getId()).setName(user.getNickname())).toList());
    }

    private MesProWorkbenchTaskRespVO buildTask(MesProTaskDO task, MesMdWorkstationDO workstation,
                                               MesProWorkOrderDO order, MesMdItemDO item, MesProProcessDO process) {
        return BeanUtils.toBean(task, MesProWorkbenchTaskRespVO.class)
                .setWorkstationName(workstation.getName()).setWorkOrderCode(order.getCode()).setWorkOrderName(order.getName())
                .setItemCode(item != null ? item.getCode() : null).setItemName(item != null ? item.getName() : null)
                .setItemSpecification(item != null ? item.getSpecification() : null)
                .setProcessName(process != null ? process.getName() : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitFeedback(MesProWorkbenchFeedbackReqVO reqVO) {
        Long userId = getLoginUserId();
        if (userId == null) throw exception(UNAUTHORIZED);
        MesProWorkbenchTaskRespVO task = getContext(reqVO.getTaskId()).getTask();
        userApi.validateUser(reqVO.getApproveUserId());
        BigDecimal qualified = Objects.requireNonNullElse(reqVO.getQualifiedQuantity(), BigDecimal.ZERO);
        BigDecimal unqualified = Objects.requireNonNullElse(reqVO.getUnqualifiedQuantity(), BigDecimal.ZERO);
        BigDecimal quantity = task.getCheckFlag() ? reqVO.getFeedbackQuantity() : qualified.add(unqualified);
        if (qualified.signum() < 0 || unqualified.signum() < 0 || quantity == null || quantity.signum() <= 0) {
            throw exception(PRO_FEEDBACK_QUANTITY_MUST_POSITIVE);
        }
        MesProFeedbackSaveReqVO feedback = new MesProFeedbackSaveReqVO()
                .setCode(autoCodeService.generateAutoCode(MesMdAutoCodeRuleCodeEnum.PRO_FEEDBACK_CODE.getCode()))
                .setType(MesProFeedbackTypeEnum.SELF.getType()).setFeedbackUserId(userId).setApproveUserId(reqVO.getApproveUserId())
                .setTaskId(task.getId()).setWorkstationId(task.getWorkstationId()).setWorkOrderId(task.getWorkOrderId())
                .setRouteId(task.getRouteId()).setProcessId(task.getProcessId()).setItemId(task.getItemId())
                .setScheduledQuantity(task.getQuantity()).setFeedbackQuantity(quantity)
                .setQualifiedQuantity(task.getCheckFlag() ? BigDecimal.ZERO : qualified)
                .setUnqualifiedQuantity(task.getCheckFlag() ? BigDecimal.ZERO : unqualified)
                .setUncheckQuantity(task.getCheckFlag() ? quantity : BigDecimal.ZERO)
                .setFeedbackTime(LocalDateTime.now()).setRemark(reqVO.getRemark()).setSources(reqVO.getSources());
        Long id = feedbackService.createFeedback(feedback);
        feedbackService.submitFeedback(id);
        return id;
    }
}
