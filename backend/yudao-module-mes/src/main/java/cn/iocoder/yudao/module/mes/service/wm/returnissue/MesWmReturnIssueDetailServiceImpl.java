package cn.iocoder.yudao.module.mes.service.wm.returnissue;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnissue.vo.detail.MesWmReturnIssueDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue.MesWmReturnIssueDetailMapper;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 生产退料明细 Service 实现类
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Validated
public class MesWmReturnIssueDetailServiceImpl implements MesWmReturnIssueDetailService {

    @Resource
    private MesWmReturnIssueDetailMapper issueDetailMapper;

    @Resource
    @Lazy
    private MesWmReturnIssueLineService issueLineService;
    @Resource
    private MesWmWarehouseAreaService warehouseAreaService;
    @Resource
    @Lazy
    private MesWmMaterialStockService materialStockService;
    @Resource
    private MesWmWarehouseService warehouseService;
    @Resource
    @Lazy
    private MesWmReturnIssueService issueService;
    @Resource
    private cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue.MesWmReturnIssueMapper issueMapper;

    @Override
    public Long createReturnIssueDetail(MesWmReturnIssueDetailSaveReqVO createReqVO) {
        validateReturnIssueDetailSaveData(createReqVO);

        // 插入
        MesWmReturnIssueDetailDO detail = BeanUtils.toBean(createReqVO, MesWmReturnIssueDetailDO.class);
        issueDetailMapper.insert(detail);
        return detail.getId();
    }

    @Override
    public void updateReturnIssueDetail(MesWmReturnIssueDetailSaveReqVO updateReqVO) {
        // 校验存在
        validateReturnIssueDetailExists(updateReqVO.getId());
        var persisted = issueDetailMapper.selectById(updateReqVO.getId());
        var parent = issueMapper.selectByIdForUpdate(persisted.getIssueId());
        if (parent == null || !java.util.Objects.equals(parent.getStatus(),
                cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnIssueStatusEnum.APPROVING.getStatus())
                || !java.util.Objects.equals(persisted.getIssueId(), updateReqVO.getIssueId())
                || !java.util.Objects.equals(persisted.getLineId(), updateReqVO.getLineId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        validateReturnIssueDetailSaveData(updateReqVO);

        // 更新
        MesWmReturnIssueDetailDO updateObj = BeanUtils.toBean(updateReqVO, MesWmReturnIssueDetailDO.class);
        issueDetailMapper.updateById(updateObj);
    }

    @Override
    public void deleteReturnIssueDetail(Long id) {
        // 校验存在
        validateReturnIssueDetailExists(id);
        var detail = issueDetailMapper.selectById(id);
        var issue = issueMapper.selectByIdForUpdate(detail.getIssueId());
        if (issue == null || !java.util.Objects.equals(issue.getStatus(),
                cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnIssueStatusEnum.APPROVING.getStatus())) {
            throw exception(WM_RETURN_ISSUE_NOT_APPROVING);
        }
        // 删除
        issueDetailMapper.deleteById(id);
    }

    @Override
    public MesWmReturnIssueDetailDO getReturnIssueDetail(Long id) {
        return issueDetailMapper.selectById(id);
    }

    @Override
    public List<MesWmReturnIssueDetailDO> getReturnIssueDetailListByLineId(Long lineId) {
        return issueDetailMapper.selectListByLineId(lineId);
    }

    @Override
    public List<MesWmReturnIssueDetailDO> getReturnIssueDetailListByIssueId(Long issueId) {
        return issueDetailMapper.selectListByIssueId(issueId);
    }

    @Override
    public void deleteReturnIssueDetailByIssueId(Long issueId) {
        issueDetailMapper.deleteByIssueId(issueId);
    }

    @Override
    public void deleteReturnIssueDetailByLineId(Long lineId) {
        issueDetailMapper.deleteByLineId(lineId);
    }

    private void validateReturnIssueDetailExists(Long id) {
        if (issueDetailMapper.selectById(id) == null) {
            throw exception(WM_RETURN_ISSUE_DETAIL_NOT_EXISTS);
        }
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateReturnIssueDetailSaveData(MesWmReturnIssueDetailSaveReqVO reqVO) {
        // 校验父数据存在
        MesWmReturnIssueLineDO line = issueLineService.validateReturnIssueLineExists(reqVO.getLineId());
        if (ObjUtil.notEqual(line.getIssueId(), reqVO.getIssueId())) {
            throw exception(WM_RETURN_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        if (ObjUtil.notEqual(line.getItemId(), reqVO.getItemId())) {
            throw exception(WM_RETURN_ISSUE_DETAIL_ITEM_MISMATCH);
        }
        var issue = issueMapper.selectByIdForUpdate(reqVO.getIssueId());
        if (issue == null || !java.util.Objects.equals(issue.getStatus(),
                cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnIssueStatusEnum.APPROVING.getStatus())
                || ObjUtil.notEqual(line.getMaterialStockId(), reqVO.getMaterialStockId())
                || ObjUtil.notEqual(line.getBatchId(), reqVO.getBatchId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        materialStockService.validateWorkOrderStock(line.getMaterialStockId(), issue.getWorkOrderId(),
                line.getItemId(), line.getBatchId(), reqVO.getQuantity());
        cn.iocoder.yudao.module.mes.service.wm.quality.MesWmQualityPolicy.validateInbound(
                line.getQualityStatus(), warehouseService.validateWarehouseExists(reqVO.getWarehouseId()));
        if (MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE.equals(
                warehouseService.validateWarehouseExists(reqVO.getWarehouseId()).getCode())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        // 校验仓库、库区、库位的关联关系
        warehouseAreaService.validateWarehouseAreaExists(
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId());
        // 校验库位物料/批次混放规则
        materialStockService.checkAreaMixingRule(reqVO.getAreaId(), reqVO.getItemId(), reqVO.getBatchId());
        // 校验库存记录
        // 校验明细总数量不超过行数量（排除自身）
        validateDetailQuantityNotExceed(reqVO.getLineId(), reqVO.getQuantity(), reqVO.getId(), line);
    }

    /**
     * 校验明细总数量不超过行数量
     *
     * @param lineId 行 ID
     * @param newQuantity 本次新增/修改的数量
     * @param excludeDetailId 排除的明细 ID（更新时排除自身，新增时传 null）
     * @param line 退料单行
     */
    private void validateDetailQuantityNotExceed(Long lineId, BigDecimal newQuantity,
                                                  Long excludeDetailId, MesWmReturnIssueLineDO line) {
        // 计算已有明细总数量（排除自身）
        List<MesWmReturnIssueDetailDO> details = issueDetailMapper.selectListByLineId(lineId);
        BigDecimal existingTotal = CollectionUtils.getSumValue(details,
                detail -> excludeDetailId != null && excludeDetailId.equals(detail.getId())
                        ? BigDecimal.ZERO : detail.getQuantity(),
                BigDecimal::add, BigDecimal.ZERO);
        // 校验：已有 + 本次 <= 行数量
        if (existingTotal.add(newQuantity).compareTo(line.getQuantity()) > 0) {
            throw exception(WM_RETURN_ISSUE_DETAIL_QUANTITY_EXCEED);
        }
    }

}
