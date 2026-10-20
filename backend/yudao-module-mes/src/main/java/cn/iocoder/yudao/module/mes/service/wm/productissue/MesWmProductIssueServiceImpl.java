package cn.iocoder.yudao.module.mes.service.wm.productissue;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.MesWmProductIssuePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.MesWmProductIssueSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productissue.MesWmProductIssueMapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductIssueStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmTransactionTypeEnum;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseLocationService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseAreaDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseLocationDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 领料出库单 Service 实现类
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Validated
public class MesWmProductIssueServiceImpl implements MesWmProductIssueService {

    @Resource
    private MesWmProductIssueMapper issueMapper;

    @Resource
    private MesWmProductIssueLineService issueLineService;
    @Resource
    private MesWmProductIssueDetailService issueDetailService;
    @Resource
    private MesMdWorkstationService workstationService;
    @Resource
    private MesProWorkOrderService workOrderService;
    @Resource
    private MesWmTransactionService wmTransactionService;
    @Resource
    private MesWmWarehouseService warehouseService;
    @Resource
    private MesWmWarehouseLocationService locationService;
    @Resource
    private MesWmWarehouseAreaService areaService;
    @Resource
    private MesProKittingService kittingService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProductIssue(MesWmProductIssueSaveReqVO createReqVO) {
        // 1. 校验关联数据
        validateProductIssueSaveData(createReqVO);

        // 2. 插入主表
        MesWmProductIssueDO issue = BeanUtils.toBean(createReqVO, MesWmProductIssueDO.class);
        issue.setStatus(MesWmProductIssueStatusEnum.PREPARE.getStatus());
        issueMapper.insert(issue);
        return issue.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProductIssue(MesWmProductIssueSaveReqVO updateReqVO) {
        // 1.1 校验存在 + 准备中状态
        MesWmProductIssueDO current=validateProductIssueExistsAndPrepare(updateReqVO.getId());
        if(!Objects.equals(current.getWorkOrderId(),updateReqVO.getWorkOrderId())
                && !issueLineService.getProductIssueLineListByIssueIdForUpdate(current.getId()).isEmpty()) {
            throw exception(WM_PRODUCT_ISSUE_HAS_LINES);
        }
        // 1.2 校验关联数据
        validateProductIssueSaveData(updateReqVO);

        // 2. 更新主表
        MesWmProductIssueDO updateObj = BeanUtils.toBean(updateReqVO, MesWmProductIssueDO.class);
        issueMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductIssue(Long id) {
        // 1. 校验存在 + 准备中状态
        validateProductIssueExistsAndPrepare(id);

        // The cascade independently locks, validates and removes details before lines.
        issueLineService.deleteProductIssueLineByIssueId(id);
        // 2.3 删除主表
        issueMapper.deleteById(id);
    }

    @Override
    public MesWmProductIssueDO getProductIssue(Long id) {
        return issueMapper.selectById(id);
    }

    @Override
    public PageResult<MesWmProductIssueDO> getProductIssuePage(MesWmProductIssuePageReqVO pageReqVO) {
        return issueMapper.selectPage(pageReqVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitProductIssue(Long id) {
        // 校验存在 + 草稿状态
        validateProductIssueExistsAndPrepare(id);
        // 校验至少有一条行
        List<MesWmProductIssueLineDO> lines = issueLineService.getProductIssueLineListByIssueIdForUpdate(id);
        if (CollUtil.isEmpty(lines)) {
            throw exception(WM_PRODUCT_ISSUE_NO_LINE);
        }

        // 提交（草稿 → 待拣货）
        issueMapper.updateById(new MesWmProductIssueDO()
                .setId(id).setStatus(MesWmProductIssueStatusEnum.APPROVING.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stockProductIssue(Long id) {
        // 校验存在
        MesWmProductIssueDO issue = validateProductIssueExists(id);
        if (ObjUtil.notEqual(MesWmProductIssueStatusEnum.APPROVING.getStatus(), issue.getStatus())) {
            throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        }
        // 校验拣货明细闭环：行数量 = 明细数量
        lockAndValidateDetails(id);

        // 执行拣货（待拣货 → 待执行领出）
        issueMapper.updateById(new MesWmProductIssueDO()
                .setId(id).setStatus(MesWmProductIssueStatusEnum.APPROVED.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void finishProductIssue(Long id) {
        // Match reservation and closure lock order: order -> issue -> stock.
        MesWmProductIssueDO snapshot = issueMapper.selectById(id);
        if (snapshot == null) throw exception(WM_PRODUCT_ISSUE_NOT_EXISTS);
        var workOrder = workOrderService.validateWorkOrderExistsForUpdate(snapshot.getWorkOrderId());
        if (!java.util.Objects.equals(workOrder.getStatus(), 1)) throw exception(PRO_WORK_ORDER_NOT_CONFIRMED);
        MesWmProductIssueDO issue = issueMapper.selectByIdForUpdate(id);
        if (issue == null) throw exception(WM_PRODUCT_ISSUE_NOT_EXISTS);
        if (!java.util.Objects.equals(issue.getWorkOrderId(), snapshot.getWorkOrderId())) {
            throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        }
        if (ObjUtil.notEqual(MesWmProductIssueStatusEnum.APPROVED.getStatus(), issue.getStatus())) {
            throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        }
        List<MesWmProductIssueDetailDO> details=lockAndValidateDetails(id);

        // 2. 遍历所有明细，创建库存事务（扣减库存 + 记录流水）
        createTransactionList(issue,details);
        kittingService.releaseExcessAfterIssue(issue.getWorkOrderId());

        // 3. 更新出库单状态 + 领料日期
        issueMapper.updateById(new MesWmProductIssueDO()
                .setId(id).setStatus(MesWmProductIssueStatusEnum.FINISHED.getStatus())
                .setIssueDate(LocalDateTime.now()));
    }

    private void createTransactionList(MesWmProductIssueDO issue,List<MesWmProductIssueDetailDO> details) {
        // 1. 查询虚拟线边库
        MesWmWarehouseDO virtualWarehouse = warehouseService.getWarehouseByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE);
        MesWmWarehouseLocationDO virtualLocation = locationService.getWarehouseLocationByCode(MesWmWarehouseLocationDO.WIP_VIRTUAL_LOCATION);
        MesWmWarehouseAreaDO virtualArea = areaService.getWarehouseAreaByCode(MesWmWarehouseAreaDO.WIP_VIRTUAL_AREA);

        // 2. 遍历明细，每条明细产生 OUT（实际仓库扣减）+ IN（虚拟线边库增加）
        List<Long> areas = new java.util.ArrayList<>();
        areas.add(virtualArea.getId());
        details.stream().map(MesWmProductIssueDetailDO::getAreaId).forEach(areas::add);
        kittingService.lockForIssue(issue.getWorkOrderId(), areas);
        for (MesWmProductIssueDetailDO detail : details) {
            // 2.1 先从实际仓库出库（库存减少）
            Long outTransactionId = wmTransactionService.createTransaction(new MesWmTransactionSaveReqDTO()
                    .setType(MesWmTransactionTypeEnum.OUT.getType()).setItemId(detail.getItemId())
                .setMaterialStockId(detail.getMaterialStockId())
                    .setQuantity(detail.getQuantity().negate()) // 库存减少
                    .setWorkOrderId(issue.getWorkOrderId())
                    .setBatchId(detail.getBatchId()).setBatchCode(detail.getBatchCode())
                    .setWarehouseId(detail.getWarehouseId()).setLocationId(detail.getLocationId()).setAreaId(detail.getAreaId())
                    .setBizType(MesBizTypeConstants.WM_ISSUE).setBizId(issue.getId())
                    .setBizCode(issue.getCode()).setBizLineId(detail.getLineId()));
            // 2.2 再入虚拟线边库（库存增加）
            wmTransactionService.createTransaction(new MesWmTransactionSaveReqDTO()
                    .setType(MesWmTransactionTypeEnum.IN.getType()).setItemId(detail.getItemId())
                    .setQuantity(detail.getQuantity()) // 库存增加
                    .setBatchId(detail.getBatchId()).setBatchCode(detail.getBatchCode())
                    .setWarehouseId(virtualWarehouse.getId()).setLocationId(virtualLocation.getId()).setAreaId(virtualArea.getId())
                    .setWorkOrderId(issue.getWorkOrderId())
                    .setBizType(MesBizTypeConstants.WM_ISSUE).setBizId(issue.getId())
                    .setBizCode(issue.getCode()).setBizLineId(detail.getLineId())
                    .setRelatedTransactionId(outTransactionId)); // 关联出库事务
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelProductIssue(Long id) {
        // 校验存在
        MesWmProductIssueDO issue = validateProductIssueExists(id);
        // 已完成和已取消不允许取消
        if (ObjectUtils.equalsAny(issue.getStatus(),
                MesWmProductIssueStatusEnum.FINISHED.getStatus(),
                MesWmProductIssueStatusEnum.CANCELED.getStatus())) {
            throw exception(WM_PRODUCT_ISSUE_CANCEL_NOT_ALLOWED);
        }

        // 取消
        issueMapper.updateById(new MesWmProductIssueDO()
                .setId(id).setStatus(MesWmProductIssueStatusEnum.CANCELED.getStatus()));
    }

    @Override
    public Boolean checkProductIssueQuantity(Long id) {
        validateProductIssueExists(id);
        try {lockAndValidateDetails(id);return true;}
        catch(cn.iocoder.yudao.framework.common.exception.ServiceException error) {
            if(Objects.equals(error.getCode(),WM_PRODUCT_ISSUE_DETAIL_QUANTITY_MISMATCH.getCode())
                    || Objects.equals(error.getCode(),WM_PRODUCT_ISSUE_NO_DETAIL.getCode())
                    || Objects.equals(error.getCode(),WM_PRODUCT_ISSUE_NO_LINE.getCode()))return false;
            throw error;
        }
    }

    /** Caller holds issue (and order for posting). All validation and posting use these current rows. */
    private List<MesWmProductIssueDetailDO> lockAndValidateDetails(Long issueId) {
        List<MesWmProductIssueLineDO> lines=issueLineService.getProductIssueLineListByIssueIdForUpdate(issueId);
        if(lines.isEmpty())throw exception(WM_PRODUCT_ISSUE_NO_LINE);
        Map<Long,MesWmProductIssueLineDO> byId=lines.stream().collect(Collectors.toMap(MesWmProductIssueLineDO::getId,Function.identity()));
        Map<Long,MesWmProductIssueDetailDO> details=new LinkedHashMap<>();
        for(var row:issueDetailService.getProductIssueDetailListByIssueIdForUpdate(issueId)) {
            // Reject our own cross-parent references before looking at another line's details.
            // Otherwise two corrupt parents could each hold one detail and wait on the other's.
            if(!Objects.equals(row.getIssueId(),issueId) || !byId.containsKey(row.getLineId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
            details.put(row.getId(),row);
        }
        for(var line:lines) {
            if(!Objects.equals(line.getIssueId(),issueId))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
            // Include a legacy wrong-issue detail referencing one of our real lines, then reject it.
            issueDetailService.getProductIssueDetailListByLineIdForUpdate(line.getId()).forEach(row->details.put(row.getId(),row));
        }
        if(details.isEmpty())throw exception(WM_PRODUCT_ISSUE_NO_DETAIL);
        Map<Long,BigDecimal> quantities=new LinkedHashMap<>();
        for(var detail:details.values()) {
            MesWmProductIssueLineDO line=byId.get(detail.getLineId());
            if(line==null || !Objects.equals(detail.getIssueId(),issueId))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
            if(!Objects.equals(detail.getItemId(),line.getItemId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_ITEM_MISMATCH);
            if(line.getBatchId()!=null && !Objects.equals(detail.getBatchId(),line.getBatchId()))throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
            if(!validQuantity(detail.getQuantity()))throw exception(WM_PRODUCT_ISSUE_DETAIL_QUANTITY_MISMATCH);
            quantities.merge(line.getId(),detail.getQuantity(),BigDecimal::add);
        }
        for(var line:lines)if(!validQuantity(line.getQuantity()) || line.getQuantity().compareTo(quantities.getOrDefault(line.getId(),BigDecimal.ZERO))!=0)throw exception(WM_PRODUCT_ISSUE_DETAIL_QUANTITY_MISMATCH);
        return List.copyOf(details.values());
    }
    private boolean validQuantity(BigDecimal quantity) {
        return quantity!=null && quantity.signum()>0 && quantity.scale()<=6 && quantity.precision()-quantity.scale()<=14;
    }

    @Override
    public MesWmProductIssueDO validateProductIssueExists(Long id) {
        MesWmProductIssueDO issue = issueMapper.selectByIdForUpdate(id);
        if (issue == null) {
            throw exception(WM_PRODUCT_ISSUE_NOT_EXISTS);
        }
        return issue;
    }

    /**
     * 校验领料出库单存在且为准备中状态
     */
    @Override
    public MesWmProductIssueDO validateProductIssueExistsAndPrepare(Long id) {
        MesWmProductIssueDO issue = validateProductIssueExists(id);
        if (ObjUtil.notEqual(MesWmProductIssueStatusEnum.PREPARE.getStatus(), issue.getStatus())) {
            throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        }
        return issue;
    }

    /**
     * 校验编码唯一性
     */
    private void validateCodeUnique(Long id, String code) {
        MesWmProductIssueDO issue = issueMapper.selectByCode(code);
        if (issue == null) {
            return;
        }
        if (ObjUtil.notEqual(id, issue.getId())) {
            throw exception(WM_PRODUCT_ISSUE_CODE_DUPLICATE);
        }
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateProductIssueSaveData(MesWmProductIssueSaveReqVO reqVO) {
        validateCodeUnique(reqVO.getId(), reqVO.getCode());
        workOrderService.validateWorkOrderConfirmed(reqVO.getWorkOrderId());
        if (reqVO.getWorkstationId() != null) {
            workstationService.validateWorkstationExistsAndEnable(reqVO.getWorkstationId());
        }
    }

}
