package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.detail.MesWmReturnSalesDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesDetailMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnSalesStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
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
 * MES 销售退货明细 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesWmReturnSalesDetailServiceImpl implements MesWmReturnSalesDetailService {

    @Resource
    private MesWmReturnSalesDetailMapper returnSalesDetailMapper;

    @Resource
    @Lazy
    private MesWmReturnSalesLineService returnSalesLineService;
    @Resource
    @Lazy
    private MesWmReturnSalesService returnSalesService;
    @Resource
    private MesWmWarehouseAreaService warehouseAreaService;
    @Resource
    private MesWmBatchService batchService;
    @Resource
    private MesWmReturnSalesDeliveryService deliveryService;
    @Resource private MesWmReturnSalesSourceService sourceService;
    @Resource
    private cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesLineMapper returnSalesLineMapper;
    @Resource
    @Lazy
    private MesWmMaterialStockService materialStockService;
    @Resource
    private cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService warehouseService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createReturnSalesDetail(MesWmReturnSalesDetailSaveReqVO createReqVO) {
        // 校验保存数据
        validateReturnSalesDetailSaveData(createReqVO);

        // 插入
        MesWmReturnSalesDetailDO detail = BeanUtils.toBean(createReqVO, MesWmReturnSalesDetailDO.class);
        returnSalesDetailMapper.insert(detail);
        return detail.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateReturnSalesDetail(MesWmReturnSalesDetailSaveReqVO updateReqVO) {
        // 校验存在
        var persisted = validateReturnSalesDetailExists(updateReqVO.getId());
        Long originalParent = persisted.getReturnId(), originalLine = persisted.getLineId();
        validateReturnSalesStatusApproved(persisted.getReturnId());
        sourceService.lockForRqc(originalParent,originalLine);
        persisted = returnSalesDetailMapper.selectByIdForUpdate(updateReqVO.getId());
        if (persisted == null) throw exception(WM_RETURN_SALES_DETAIL_NOT_EXISTS);
        if (!java.util.Objects.equals(originalParent, persisted.getReturnId())
                || !java.util.Objects.equals(originalLine, persisted.getLineId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        if (!java.util.Objects.equals(persisted.getReturnId(), updateReqVO.getReturnId())
                || !java.util.Objects.equals(persisted.getLineId(), updateReqVO.getLineId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        // 校验保存数据
        validateReturnSalesDetailSaveData(updateReqVO);

        // 更新
        MesWmReturnSalesDetailDO updateObj = BeanUtils.toBean(updateReqVO, MesWmReturnSalesDetailDO.class);
        returnSalesDetailMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnSalesDetail(Long id) {
        // 校验存在
        MesWmReturnSalesDetailDO detail = validateReturnSalesDetailExists(id);
        // 校验退货单为待上架状态才允许删除明细
        validateReturnSalesStatusApproved(detail.getReturnId());
        sourceService.lockForRqc(detail.getReturnId(),detail.getLineId());
        var current = returnSalesDetailMapper.selectByIdForUpdate(id);
        if (current == null || !java.util.Objects.equals(detail.getReturnId(), current.getReturnId())
                || !java.util.Objects.equals(detail.getLineId(), current.getLineId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }

        // 删除
        returnSalesDetailMapper.deleteById(id);
    }

    @Override
    public MesWmReturnSalesDetailDO getReturnSalesDetail(Long id) {
        return returnSalesDetailMapper.selectById(id);
    }

    @Override
    public List<MesWmReturnSalesDetailDO> getReturnSalesDetailListByLineId(Long lineId) {
        return returnSalesDetailMapper.selectListByLineId(lineId);
    }

    @Override
    public List<MesWmReturnSalesDetailDO> getReturnSalesDetailListByReturnId(Long returnId) {
        return returnSalesDetailMapper.selectListByReturnId(returnId);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnSalesDetailByReturnId(Long returnId) {
        returnSalesService.validateReturnSalesExistsAndPrepare(returnId);
        sourceService.lockDocumentSources(returnId);
        sourceService.requireNoPosting(returnId);
        returnSalesDetailMapper.deleteByReturnId(returnId);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnSalesDetailByLineId(Long lineId) {
        var line=returnSalesLineService.getReturnSalesLine(lineId);
        if(line==null)throw exception(WM_RETURN_SALES_LINE_NOT_EXISTS);
        returnSalesService.validateReturnSalesExistsAndPrepare(line.getReturnId());
        sourceService.lockForRqc(line.getReturnId(),lineId);
        returnSalesDetailMapper.deleteByLineId(lineId);
    }

    private MesWmReturnSalesDetailDO validateReturnSalesDetailExists(Long id) {
        MesWmReturnSalesDetailDO detail = returnSalesDetailMapper.selectById(id);
        if (detail == null) {
            throw exception(WM_RETURN_SALES_DETAIL_NOT_EXISTS);
        }
        return detail;
    }

    /**
     * 校验退货单为待上架状态（明细操作仅允许在待上架状态）
     */
    private void validateReturnSalesStatusApproved(Long returnId) {
        MesWmReturnSalesDO returnSales = returnSalesService.validateReturnSalesExists(returnId);
        if (ObjUtil.notEqual(MesWmReturnSalesStatusEnum.APPROVED.getStatus(), returnSales.getStatus())) {
            throw exception(WM_RETURN_SALES_STATUS_NOT_APPROVED);
        }
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateReturnSalesDetailSaveData(MesWmReturnSalesDetailSaveReqVO reqVO) {
        // 校验父数据存在
        MesWmReturnSalesLineDO line = returnSalesLineService.getReturnSalesLine(reqVO.getLineId());
        if (line == null) throw exception(WM_RETURN_SALES_LINE_NOT_EXISTS);
        // 校验退货单为待上架状态
        validateReturnSalesStatusApproved(line.getReturnId());
        Long originalParent = line.getReturnId();
        Long originalOrigin=line.getOriginTransactionId();
        sourceService.lockOriginIds(java.util.Collections.singleton(originalOrigin));
        line = returnSalesLineMapper.selectByIdForUpdate(reqVO.getLineId());
        if (line == null || !java.util.Objects.equals(line.getReturnId(), originalParent)||!java.util.Objects.equals(line.getOriginTransactionId(),originalOrigin)) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        if (!java.util.Objects.equals(line.getReturnId(), reqVO.getReturnId())
                || !java.util.Objects.equals(line.getItemId(), reqVO.getItemId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        fillBatchId(reqVO);
        if (reqVO.getBatchId() != null && StrUtil.isBlank(reqVO.getBatchCode())) {
            reqVO.setBatchCode(batchService.validateBatchExists(reqVO.getBatchId(), reqVO.getItemId()).getCode());
        }
        deliveryService.validateBatchCode(reqVO.getItemId(), reqVO.getBatchId(), reqVO.getBatchCode());
        if (!java.util.Objects.equals(line.getBatchId(), reqVO.getBatchId())
                || reqVO.getQuantity() == null || reqVO.getQuantity().signum() <= 0) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        var parent = returnSalesService.validateReturnSalesExists(originalParent);
        sourceService.validateLine(parent,line);
        cn.iocoder.yudao.module.mes.service.wm.quality.MesWmQualityPolicy.validateInbound(
                line.getQualityStatus(), warehouseService.validateWarehouseExists(reqVO.getWarehouseId()));
        // 校验仓库、库区、库位的关联关系
        warehouseAreaService.validateWarehouseAreaExists(
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId());
        // 校验库位物料/批次混放规则
        materialStockService.checkAreaMixingRule(reqVO.getAreaId(), reqVO.getItemId(), reqVO.getBatchId());
        // 校验明细总数量不超过行数量（排除自身）
        validateDetailQuantityNotExceed(reqVO.getLineId(), reqVO.getQuantity(), reqVO.getId(), line);

    }

    /**
     * 校验明细总数量不超过行数量
     *
     * @param lineId          行 ID
     * @param newQuantity     本次新增/修改的数量
     * @param excludeDetailId 排除的明细 ID（更新时排除自身，新增时传 null）
     * @param line            退货单行
     */
    private void validateDetailQuantityNotExceed(Long lineId, BigDecimal newQuantity,
                                                  Long excludeDetailId, MesWmReturnSalesLineDO line) {
        // 计算已有明细总数量（排除自身）
        List<MesWmReturnSalesDetailDO> details = returnSalesDetailMapper.selectListByLineIdForUpdate(lineId);
        BigDecimal existingTotal = CollectionUtils.getSumValue(details,
                detail -> excludeDetailId != null && excludeDetailId.equals(detail.getId())
                        ? BigDecimal.ZERO : detail.getQuantity(),
                BigDecimal::add, BigDecimal.ZERO);
        // 校验：已有 + 本次 <= 行数量
        if (existingTotal.add(newQuantity).compareTo(line.getQuantity()) > 0) {
            throw exception(WM_RETURN_SALES_DETAIL_QUANTITY_EXCEED);
        }
    }

    /**
     * 根据 batchCode 解析并回填 batchId
     */
    private void fillBatchId(MesWmReturnSalesDetailSaveReqVO reqVO) {
        if (StrUtil.isBlank(reqVO.getBatchCode())) {
            return;
        }
        MesWmBatchDO batch = batchService.getBatchByCode(reqVO.getBatchCode());
        if (batch == null) throw exception(WM_BATCH_NOT_EXISTS);
        if (reqVO.getBatchId() != null && !java.util.Objects.equals(reqVO.getBatchId(), batch.getId())) {
            throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        reqVO.setBatchId(batch.getId());
    }

}
