package cn.iocoder.yudao.module.mes.service.pro.feedback;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemconsume.MesWmItemConsumeDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.route.MesProRouteProcessService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.itemconsume.MesWmItemConsumeService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceLineService;
import cn.iocoder.yudao.module.mes.service.wm.productproduce.MesWmProductProduceService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 生产报工 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesProFeedbackServiceImpl implements MesProFeedbackService {

    @Resource
    private MesProFeedbackMapper feedbackMapper;

    @Resource
    private MesProWorkOrderService workOrderService;
    @Resource
    private MesProRouteProcessService routeProcessService;
    @Resource
    private MesMdWorkstationService workstationService;
    @Resource
    @Lazy // 避免循环依赖
    private MesProTaskService taskService;
    @Resource
    private MesWmItemConsumeService itemConsumeService;
    @Resource
    private MesWmProductProduceService productProduceService;
    @Resource
    private MesWmProductProduceLineService produceLineService;
    @Resource
    private MesProSopService sopService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFeedback(MesProFeedbackSaveReqVO createReqVO) {
        // 和提交/审批使用相同锁序，避免工单关闭或任务变更与保存草稿交错。
        workOrderService.validateWorkOrderExistsForUpdate(createReqVO.getWorkOrderId());
        // 1. 校验
        MesProTaskDO task = validateFeedbackData(createReqVO);

        // 2. 插入（自动填充 itemId）
        MesProFeedbackDO feedback = BeanUtils.toBean(createReqVO, MesProFeedbackDO.class)
                .setStatus(MesProFeedbackStatusEnum.PREPARE.getStatus())
                .setItemId(task.getItemId());
        feedbackMapper.insert(feedback);
        return feedback.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFeedback(MesProFeedbackSaveReqVO updateReqVO) {
        // 等待提交/审批的锁之后，必须重读草稿状态，禁止审批后覆盖原始数量。
        MesProFeedbackDO feedback = lockFeedbackWorkOrder(updateReqVO.getId());
        validateFeedbackStatusPrepare(feedback);
        // 已保存报工的工单归属固定；如选错工单，应删除草稿后重新创建。
        if (ObjUtil.notEqual(feedback.getWorkOrderId(), updateReqVO.getWorkOrderId())) {
            throw exception(PRO_TASK_WORK_ORDER_MISMATCH);
        }
        // 1.2 校验业务数据
        MesProTaskDO task = validateFeedbackData(updateReqVO);

        // 2. 更新（自动填充 itemId）
        MesProFeedbackDO updateObj = BeanUtils.toBean(updateReqVO, MesProFeedbackDO.class)
                .setItemId(task.getItemId());
        feedbackMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFeedback(Long id) {
        // 1. 校验存在 + 草稿状态
        validateFeedbackStatusPrepare(lockFeedbackWorkOrder(id));

        // 2. 删除
        feedbackMapper.deleteById(id);
    }

    @Override
    public MesProFeedbackDO getFeedback(Long id) {
        return feedbackMapper.selectById(id);
    }

    @Override
    public PageResult<MesProFeedbackDO> getFeedbackPage(MesProFeedbackPageReqVO pageReqVO) {
        return feedbackMapper.selectPage(pageReqVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitFeedback(Long id) {
        // A delayed duplicate submit must not reopen an already approved report.
        // Use the same work-order -> feedback lock order as approval, then read
        // the current status after waiting for those locks.
        MesProFeedbackDO feedback = lockFeedbackWorkOrder(id);
        if (ObjUtil.notEqual(feedback.getStatus(), MesProFeedbackStatusEnum.PREPARE.getStatus())) {
            throw exception(PRO_FEEDBACK_NOT_PREPARE);
        }
        // Resources may change while a draft is saved. Enforce availability at
        // production submission, while allowing later approval across shifts.
        workstationService.validateWorkstationAvailable(feedback.getWorkstationId());
        taskService.validateTaskNotFinishedForUpdate(feedback.getTaskId(), feedback.getWorkOrderId());
        sopService.validateTaskExecution(feedback.getTaskId());
        validateSavedFeedbackQuantities(feedback);

        // 2. 更新状态为审批中（报工人和报工时间由表单保存时确定，提交不覆盖）
        feedbackMapper.updateById(new MesProFeedbackDO().setId(id)
                .setStatus(MesProFeedbackStatusEnum.APPROVING.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectFeedback(Long id) {
        // 1. 校验存在 + 审批中状态
        MesProFeedbackDO feedback = lockFeedbackWorkOrder(id);
        if (ObjUtil.notEqual(feedback.getStatus(), MesProFeedbackStatusEnum.APPROVING.getStatus())) {
            throw exception(PRO_FEEDBACK_NOT_APPROVING);
        }
        validateFeedbackApprover(feedback);

        // 2. 更新状态为草稿
        feedbackMapper.updateById(new MesProFeedbackDO().setId(id)
                .setStatus(MesProFeedbackStatusEnum.PREPARE.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean approveFeedback(Long id) {
        // 1.1 统一按工单→报工→任务加锁，并在等锁后校验最新报工状态。
        MesProFeedbackDO feedback = lockFeedbackWorkOrder(id);
        if (ObjUtil.notEqual(feedback.getStatus(), MesProFeedbackStatusEnum.APPROVING.getStatus())) {
            throw exception(PRO_FEEDBACK_NOT_APPROVING);
        }
        validateFeedbackApprover(feedback);
        // 1.2 校验报工数量 > 0
        if (feedback.getFeedbackQuantity() == null
                || feedback.getFeedbackQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw exception(PRO_FEEDBACK_QUANTITY_MUST_POSITIVE);
        }
        // 1.3 校验任务未完成
        taskService.validateTaskNotFinishedForUpdate(feedback.getTaskId(), feedback.getWorkOrderId());
        sopService.validateTaskExecution(feedback.getTaskId());

        // 2.1 查询工序的关键工序标识 + 检验标识（需在 uncheckQuantity 校验之前，因为质检工序允许 uncheckQuantity > 0）
        MesProRouteProcessDO routeProcess = validateSavedFeedbackQuantities(feedback);
        taskService.validateFeedbackSequence(feedback);
        boolean keyFlag = Boolean.TRUE.equals(routeProcess.getKeyFlag());
        boolean checkFlag = Boolean.TRUE.equals(routeProcess.getCheckFlag());

        // 3. 物料消耗：根据工序 BOM 生成消耗记录并执行扣减
        MesWmItemConsumeDO itemConsume = itemConsumeService.generateItemConsume(feedback);
        if (itemConsume != null) {
            itemConsumeService.finishItemConsume(itemConsume.getId());
        }

        // 4. 关键工序：生成产出单，并根据是否需要检验决定入库方式
        if (keyFlag) {
            // 4.1 需要检验：生成产出单（质量状态=待检验），更新报工状态为待检验，等质检完成回调后再入库
            if (checkFlag) {
                // 完成时回调见：MesQcIpqcServiceImpl#finishIpqc → splitPendingAndFinishProduce + completeFeedbackFromIpqc
                productProduceService.generateProductProduce(feedback, true);
                feedbackMapper.updateById(new MesProFeedbackDO().setId(id)
                        .setStatus(MesProFeedbackStatusEnum.UNCHECK.getStatus()));
                return false;
            }
            // 4.2 无需检验：生成产出单（按合格/不合格拆行），直接完成入库，并更新任务/工单的已生产数量
            MesWmProductProduceDO produce = productProduceService.generateProductProduce(feedback, false);
            productProduceService.finishProductProduce(produce.getId());
            updateTaskAndWorkOrderByFeedback(feedback);
        } else {
            // 非关键工序也有自己的生产进度，但不生成产品库存或累计工单产出。
            // 此处没有产出单行，使用报工单保存的合格/不良数量。
            taskService.updateProducedQuantity(feedback.getTaskId(), feedback.getFeedbackQuantity(),
                    ObjectUtil.defaultIfNull(feedback.getQualifiedQuantity(), BigDecimal.ZERO),
                    ObjectUtil.defaultIfNull(feedback.getUnqualifiedQuantity(), BigDecimal.ZERO));
        }

        // 5. 无需检验的工序直接完成。
        feedbackMapper.updateById(new MesProFeedbackDO().setId(id)
                .setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus())
                .setUncheckQuantity(BigDecimal.ZERO));
        return true; // 已完成
    }

    /**
     * 根据当前报工单的最终结果，更新生产任务和生产工单的进度
     *
     * <ul>
     *   <li>使用报工数量（feedbackQuantity），累加任务和工单的已生产数量</li>
     *   <li>使用产出单行按 qualityStatus 聚合的合格品/不合格品数量，累加任务的合格品/不合格品数量
     *       （不直接用 feedback 上的数量，确保质检回调场景下数量来源正确）</li>
     * </ul>
     *
     * @param feedback 报工单
     */
    private void updateTaskAndWorkOrderByFeedback(MesProFeedbackDO feedback) {
        // 1. 查询该报工单关联的所有产出单行，按质量状态聚合数量
        BigDecimal qualifiedQty = BigDecimal.ZERO;
        BigDecimal unqualifiedQty = BigDecimal.ZERO;
        List<MesWmProductProduceLineDO> lines = produceLineService.getProductProduceLineListByFeedbackId(feedback.getId());
        for (MesWmProductProduceLineDO line : lines) {
            validateQuantity(line.getQuantity());
            if (ObjUtil.equal(line.getQualityStatus(), MesWmQualityStatusEnum.FAIL.getStatus())) {
                unqualifiedQty = unqualifiedQty.add(line.getQuantity());
            }
            if (ObjUtil.equal(line.getQualityStatus(), MesWmQualityStatusEnum.PASS.getStatus())) {
                qualifiedQty = qualifiedQty.add(line.getQuantity());
            }
        }
        // 产出行是实际库存来源，和本次扣料/生产进度必须使用相同总量。
        validateClassifiedTotal(feedback.getFeedbackQuantity(), qualifiedQty, unqualifiedQty);

        // 2. 更新任务的已生产/合格/不合格数量
        taskService.updateProducedQuantity(feedback.getTaskId(),
                feedback.getFeedbackQuantity(), qualifiedQty, unqualifiedQty);
        // 3. 更新工单的已生产数量
        workOrderService.updateProducedQuantity(feedback.getWorkOrderId(),
                feedback.getFeedbackQuantity());
    }

    // ==================== 校验方法 ====================

    /**
     * 必须在任何任务/产出更新前先锁定工单，和排产、取消、完成保持一致锁序。
     * 快照只用于定位工单；持有工单锁后重读报工，防止等待期间状态或归属变化。
     */
    private MesProFeedbackDO lockFeedbackWorkOrder(Long id) {
        MesProFeedbackDO snapshot = validateFeedbackExists(id);
        workOrderService.validateWorkOrderExistsForUpdate(snapshot.getWorkOrderId());
        MesProFeedbackDO feedback = feedbackMapper.selectByIdForUpdate(id);
        if (feedback == null) {
            throw exception(PRO_FEEDBACK_NOT_EXISTS);
        }
        if (ObjUtil.notEqual(feedback.getWorkOrderId(), snapshot.getWorkOrderId())) {
            throw exception(PRO_TASK_WORK_ORDER_MISMATCH);
        }
        return feedback;
    }

    @Override
    public MesProFeedbackDO validateFeedbackExists(Long id) {
        MesProFeedbackDO feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw exception(PRO_FEEDBACK_NOT_EXISTS);
        }
        return feedback;
    }

    private void validateFeedbackStatusPrepare(MesProFeedbackDO feedback) {
        if (ObjUtil.notEqual(feedback.getStatus(), MesProFeedbackStatusEnum.PREPARE.getStatus())) {
            throw exception(PRO_FEEDBACK_NOT_PREPARE);
        }
    }

    private void validateFeedbackApprover(MesProFeedbackDO feedback) {
        Long loginUserId = getLoginUserId();
        if (loginUserId == null || !loginUserId.equals(feedback.getApproveUserId())) {
            throw exception(PRO_FEEDBACK_APPROVER_MISMATCH);
        }
    }

    /**
     * 校验报工单的业务数据（创建 & 修改共用）
     *
     * @param reqVO 报工请求
     * @return 关联的生产任务
     */
    private MesProTaskDO validateFeedbackData(MesProFeedbackSaveReqVO reqVO) {
        // 1. 校验工作站存在
        MesMdWorkstationDO workstation = workstationService.validateWorkstationExists(reqVO.getWorkstationId());
        if (ObjUtil.notEqual(workstation.getProcessId(), reqVO.getProcessId())) {
            throw exception(PRO_WORKSTATION_PROCESS_MISMATCH);
        }

        // 2.1 校验工艺路线 + 工序配置有效
        MesProRouteProcessDO routeProcess = routeProcessService.getRouteProcessByRouteIdAndProcessId(
                reqVO.getRouteId(), reqVO.getProcessId());
        if (routeProcess == null) {
            throw exception(PRO_FEEDBACK_ROUTE_PROCESS_INVALID);
        }
        validateSupportedInspection(routeProcess);
        // 2.2 校验数量，并将可省略的数量字段归零；待检量由服务端推导。
        boolean checkFlag = Boolean.TRUE.equals(routeProcess.getCheckFlag());
        if (checkFlag && reqVO.getUncheckQuantity() == null) {
            reqVO.setUncheckQuantity(reqVO.getFeedbackQuantity());
        }
        MesProFeedbackDO quantities = BeanUtils.toBean(reqVO, MesProFeedbackDO.class);
        validateFeedbackQuantities(quantities, checkFlag);
        reqVO.setQualifiedQuantity(zeroIfNull(reqVO.getQualifiedQuantity()));
        reqVO.setUnqualifiedQuantity(zeroIfNull(reqVO.getUnqualifiedQuantity()));
        reqVO.setUncheckQuantity(checkFlag ? reqVO.getFeedbackQuantity() : BigDecimal.ZERO);
        reqVO.setLaborScrapQuantity(zeroIfNull(reqVO.getLaborScrapQuantity()));
        reqVO.setMaterialScrapQuantity(zeroIfNull(reqVO.getMaterialScrapQuantity()));
        reqVO.setOtherScrapQuantity(zeroIfNull(reqVO.getOtherScrapQuantity()));

        // 3. 校验工单已确认
        MesProWorkOrderDO workOrder = workOrderService.validateWorkOrderConfirmed(reqVO.getWorkOrderId());
        if (ObjUtil.notEqual(workOrder.getProductId(), reqVO.getItemId())) {
            throw exception(PRO_WORK_ORDER_PRODUCT_MISMATCH);
        }

        // 4. 校验任务存在且未终态（已完成/已取消），并返回任务用于冗余 itemId
        MesProTaskDO task = taskService.validateTaskNotFinishedForUpdate(reqVO.getTaskId(), reqVO.getWorkOrderId());
        validateTaskRelation(task, workstation, workOrder, reqVO);
        return task;
    }

    /** 保存、提交及审批共用同一数量规则，旧草稿不能绕过最终执行边界。 */
    private MesProRouteProcessDO validateSavedFeedbackQuantities(MesProFeedbackDO feedback) {
        MesProRouteProcessDO routeProcess = routeProcessService.getRouteProcessByRouteIdAndProcessId(
                feedback.getRouteId(), feedback.getProcessId());
        if (routeProcess == null) {
            throw exception(PRO_FEEDBACK_ROUTE_PROCESS_INVALID);
        }
        validateSupportedInspection(routeProcess);
        validateFeedbackQuantities(feedback, Boolean.TRUE.equals(routeProcess.getCheckFlag()));
        return routeProcess;
    }

    private void validateSupportedInspection(MesProRouteProcessDO process) {
        // IPQC 依赖关键工序的待检产出行；非关键工序没有该来源，不能跳过检验直接完成。
        if (Boolean.TRUE.equals(process.getCheckFlag()) && !Boolean.TRUE.equals(process.getKeyFlag())) {
            throw exception(PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED);
        }
    }

    private void validateFeedbackQuantities(MesProFeedbackDO feedback, boolean checkFlag) {
        validatePositiveFeedbackQuantity(feedback.getFeedbackQuantity());
        BigDecimal qualified = zeroIfNull(feedback.getQualifiedQuantity());
        BigDecimal unqualified = zeroIfNull(feedback.getUnqualifiedQuantity());
        BigDecimal pending = zeroIfNull(feedback.getUncheckQuantity());
        validateQuantity(qualified);
        validateQuantity(unqualified);
        validateQuantity(pending);
        validateScrapQuantities(unqualified, feedback.getLaborScrapQuantity(),
                feedback.getMaterialScrapQuantity(), feedback.getOtherScrapQuantity());
        if (checkFlag) {
            // 质检前全量待检，合格/不良及其废品明细只能由 IPQC 完成后回写。
            if (qualified.signum() != 0 || unqualified.signum() != 0
                    || pending.compareTo(feedback.getFeedbackQuantity()) != 0) {
                throw exception(PRO_FEEDBACK_PENDING_QUANTITY_MISMATCH);
            }
        } else {
            if (pending.signum() > 0) {
                throw exception(PRO_FEEDBACK_UNCHECK_QUANTITY_EXISTS, pending);
            }
            validateClassifiedTotal(feedback.getFeedbackQuantity(), qualified, unqualified);
        }
    }

    private void validateClassifiedTotal(BigDecimal quantity, BigDecimal qualified, BigDecimal unqualified) {
        validatePositiveFeedbackQuantity(quantity);
        validateQuantity(qualified);
        validateQuantity(unqualified);
        if (qualified.add(unqualified).compareTo(quantity) != 0) {
            throw exception(PRO_FEEDBACK_QUALIFIED_UNQUALIFIED_MISMATCH);
        }
    }

    private void validateScrapQuantities(BigDecimal unqualified, BigDecimal labor,
                                         BigDecimal material, BigDecimal other) {
        BigDecimal laborQty = zeroIfNull(labor);
        BigDecimal materialQty = zeroIfNull(material);
        BigDecimal otherQty = zeroIfNull(other);
        validateQuantity(laborQty);
        validateQuantity(materialQty);
        validateQuantity(otherQty);
        // 废品是“不良”的原因明细，不是另一次扣料/产出，允许尚未细分原因的不良。
        if (laborQty.add(materialQty).add(otherQty).compareTo(unqualified) > 0) {
            throw exception(PRO_FEEDBACK_SCRAP_QUANTITY_EXCEED);
        }
    }

    private void validatePositiveFeedbackQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw exception(PRO_FEEDBACK_QUANTITY_MUST_POSITIVE);
        }
        validateQuantity(quantity);
    }

    private void validateQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() < 0) {
            throw exception(PRO_FEEDBACK_QUANTITY_NEGATIVE);
        }
        // 和实际 DECIMAL(20,6) 一致，避免校验通过、落库舍入后数量不再守恒。
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.scale() > 6 || normalized.precision() - normalized.scale() > 14) {
            throw exception(PRO_FEEDBACK_QUANTITY_PRECISION);
        }
    }

    private BigDecimal zeroIfNull(BigDecimal quantity) {
        return ObjectUtil.defaultIfNull(quantity, BigDecimal.ZERO);
    }

    private void validateTaskRelation(MesProTaskDO task, MesMdWorkstationDO workstation,
                                      MesProWorkOrderDO workOrder, MesProFeedbackSaveReqVO reqVO) {
        if (ObjUtil.notEqual(task.getWorkOrderId(), workOrder.getId())) {
            throw exception(PRO_TASK_WORK_ORDER_MISMATCH);
        }
        if (ObjUtil.notEqual(task.getWorkstationId(), workstation.getId())) {
            throw exception(PRO_TASK_WORKSTATION_MISMATCH);
        }
        if (ObjUtil.notEqual(task.getRouteId(), reqVO.getRouteId())
                || ObjUtil.notEqual(task.getProcessId(), reqVO.getProcessId())) {
            throw exception(PRO_TASK_ROUTE_PROCESS_MISMATCH);
        }
        if (ObjUtil.notEqual(task.getItemId(), reqVO.getItemId())) {
            throw exception(PRO_TASK_ITEM_MISMATCH);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProFeedbackWhenIpqcFinish(Long feedbackId, Long sourceLineId,
                                                BigDecimal qualifiedQty, BigDecimal unqualifiedQty,
                                                BigDecimal laborScrapQty, BigDecimal materialScrapQty, BigDecimal otherScrapQty) {
        // 1. 校验报工单存在且为待检验状态
        MesProFeedbackDO feedback = lockFeedbackWorkOrder(feedbackId);
        if (ObjUtil.notEqual(feedback.getStatus(), MesProFeedbackStatusEnum.UNCHECK.getStatus())) {
            throw exception(PRO_FEEDBACK_NOT_UNCHECK);
        }
        // 保留既有语义：工单已结束时，其待检产出仍可完成质检和库存回写。
        taskService.validateTaskExistsForUpdate(feedback.getTaskId(), feedback.getWorkOrderId());

        qualifiedQty = zeroIfNull(qualifiedQty);
        unqualifiedQty = zeroIfNull(unqualifiedQty);
        laborScrapQty = zeroIfNull(laborScrapQty);
        materialScrapQty = zeroIfNull(materialScrapQty);
        otherScrapQty = zeroIfNull(otherScrapQty);
        validateClassifiedTotal(feedback.getFeedbackQuantity(), qualifiedQty, unqualifiedQty);
        validateScrapQuantities(unqualifiedQty, laborScrapQty, materialScrapQty, otherScrapQty);

        // 2. 拆分待检产出行（合格/不合格），生成明细，完成产出入库
        productProduceService.splitPendingAndFinishProduce(feedbackId, sourceLineId, qualifiedQty, unqualifiedQty);

        // 3. 回写合格/不合格/废品数量，更新状态为已完成
        feedbackMapper.updateById(new MesProFeedbackDO().setId(feedbackId)
                .setQualifiedQuantity(qualifiedQty)
                .setUnqualifiedQuantity(unqualifiedQty)
                .setUncheckQuantity(BigDecimal.ZERO)
                .setLaborScrapQuantity(laborScrapQty)
                .setMaterialScrapQuantity(materialScrapQty)
                .setOtherScrapQuantity(otherScrapQty)
                .setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus()));

        // 4. 更新任务/工单的已生产数量
        feedback.setQualifiedQuantity(qualifiedQty).setUnqualifiedQuantity(unqualifiedQty);
        updateTaskAndWorkOrderByFeedback(feedback);
    }

}
