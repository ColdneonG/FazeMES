package cn.iocoder.yudao.module.mes.service.wm.productsales;

import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.line.MesWmProductSalesLinePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.line.MesWmProductSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesLineMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 销售出库单行 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class MesWmProductSalesLineServiceImpl implements MesWmProductSalesLineService {

    @Resource
    private MesWmProductSalesLineMapper productSalesLineMapper;

    @Resource
    private MesWmProductSalesDetailService productSalesDetailService;
    @Resource
    private MesMdItemService itemService;
    @Resource
    private MesWmBatchService batchService;
    @Resource
    @Lazy
    private MesWmProductSalesService productSalesService;
    @Resource private cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeFulfillmentService fulfillmentService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createProductSalesLine(MesWmProductSalesLineSaveReqVO createReqVO) {
        createReqVO.setId(null); // A creation request never receives the editing line's quota credit.
        // 校验数据
        validateLineSaveData(createReqVO);

        // 新增
        MesWmProductSalesLineDO line = BeanUtils.toBean(createReqVO, MesWmProductSalesLineDO.class);
        productSalesLineMapper.insert(line);
        return line.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateProductSalesLine(MesWmProductSalesLineSaveReqVO updateReqVO) {
        // A line cannot be moved out of a completed document by changing the request parent id.
        var persistedParent=productSalesLineMapper.selectById(updateReqVO.getId());
        if(persistedParent==null) throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        productSalesService.validateProductSalesExistsAndDraft(persistedParent.getSalesId());
        if(updateReqVO.getSalesId()!=null&&!Objects.equals(persistedParent.getSalesId(),updateReqVO.getSalesId()))throw exception(WM_PRODUCT_SALES_LINE_NOT_EXISTS);
        updateReqVO.setSalesId(persistedParent.getSalesId());
        // 校验存在
        MesWmProductSalesLineDO oldLine = productSalesLineMapper.selectByIdForUpdate(updateReqVO.getId());
        if(oldLine==null||!Objects.equals(oldLine.getSalesId(),persistedParent.getSalesId()))throw exception(WM_PRODUCT_SALES_LINE_NOT_EXISTS);
        // 校验数据
        updateReqVO.setSalesId(oldLine.getSalesId());
        validateLineSaveData(updateReqVO);

        // 更新
        productSalesLineMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesWmProductSalesLineDO>()
                .eq(MesWmProductSalesLineDO::getId,updateReqVO.getId()).set(MesWmProductSalesLineDO::getItemId,updateReqVO.getItemId())
                .set(MesWmProductSalesLineDO::getQuantity,updateReqVO.getQuantity()).set(MesWmProductSalesLineDO::getNoticeLineId,updateReqVO.getNoticeLineId())
                .set(MesWmProductSalesLineDO::getBatchId,updateReqVO.getBatchId()).set(MesWmProductSalesLineDO::getBatchCode,updateReqVO.getBatchCode())
                .set(MesWmProductSalesLineDO::getOqcCheckFlag,updateReqVO.getOqcCheckFlag()).set(MesWmProductSalesLineDO::getRemark,updateReqVO.getRemark()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductSalesLine(Long id) {
        // 校验存在
        MesWmProductSalesLineDO line = validateProductSalesLineExists(id);
        // 校验主单为草稿状态才允许删除行
        productSalesService.validateProductSalesExistsAndDraft(line.getSalesId());
        line=productSalesLineMapper.selectByIdForUpdate(id);
        if(line==null)throw exception(WM_PRODUCT_SALES_LINE_NOT_EXISTS);

        // 级联删除明细
        productSalesDetailService.deleteProductSalesDetailByLineId(id);
        // 删除
        productSalesLineMapper.deleteById(id);
    }

    @Override
    public MesWmProductSalesLineDO getProductSalesLine(Long id) {
        return productSalesLineMapper.selectById(id);
    }

    @Override
    public List<MesWmProductSalesLineDO> getProductSalesLineListBySalesId(Long salesId) {
        return productSalesLineMapper.selectListBySalesIdForUpdate(salesId);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteProductSalesLineBySalesId(Long salesId) {
        productSalesService.validateProductSalesExistsAndDraft(salesId);
        productSalesDetailService.deleteProductSalesDetailBySalesId(salesId);
        productSalesLineMapper.deleteBySalesId(salesId);
    }

    @Override
    public cn.iocoder.yudao.framework.common.pojo.PageResult<MesWmProductSalesLineDO> getProductSalesLinePage(
            MesWmProductSalesLinePageReqVO pageReqVO) {
        // 如果传了 clientId，先查出该客户的所有出库单 ID，设置到 salesIds
        if (pageReqVO.getSalesId() == null && pageReqVO.getClientId() != null) {
            List<MesWmProductSalesDO> salesList = productSalesService.getProductSalesListByClientId(pageReqVO.getClientId());
            if (CollUtil.isEmpty(salesList)) {
                return cn.iocoder.yudao.framework.common.pojo.PageResult.empty();
            }
            pageReqVO.setSalesIds(convertList(salesList, MesWmProductSalesDO::getId));
        }
        // 查询分页
        return productSalesLineMapper.selectPage(pageReqVO);
    }

    @Override
    public MesWmProductSalesLineDO validateProductSalesLineExists(Long id) {
        MesWmProductSalesLineDO line = productSalesLineMapper.selectById(id);
        if (line == null) {
            throw exception(WM_PRODUCT_SALES_LINE_NOT_EXISTS);
        }
        return line;
    }

    /**
     * 根据 batchCode 解析 batchId
     */
    private void fillBatchId(MesWmProductSalesLineSaveReqVO reqVO) {
        if (StrUtil.isBlank(reqVO.getBatchCode())) {
            reqVO.setBatchId(null);
            return;
        }
        MesWmBatchDO batch = batchService.getBatchByCode(reqVO.getBatchCode());
        if(batch==null||!Objects.equals(batch.getItemId(),reqVO.getItemId()))throw exception(WM_PRODUCT_SALES_LINE_NOTICE_LINE_BATCH_MISMATCH);
        reqVO.setBatchId(batch.getId());
        reqVO.setBatchCode(batch.getCode());
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateLineSaveData(MesWmProductSalesLineSaveReqVO reqVO) {
        // 校验主单存在且为草稿状态
        MesWmProductSalesDO sales = productSalesService.validateProductSalesExistsAndDraft(reqVO.getSalesId());
        // 校验物料存在
        itemService.validateItemExistsAndEnable(reqVO.getItemId());
        // 校验关联发货通知单行（存在性 + 归属 + 字段一致性）
        // 根据 batchCode 解析 batchId
        fillBatchId(reqVO);
        fulfillmentService.validateAndReserve(sales,reqVO.getId(),reqVO.getNoticeLineId(),reqVO.getItemId(),reqVO.getQuantity(),reqVO.getBatchCode(),reqVO.getOqcCheckFlag());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProductSalesLineWhenOqcFinish(Long id, Long oqcId, Integer checkResult) {
        var located=validateProductSalesLineExists(id);
        var parent=fulfillmentService.lockSales(located.getSalesId());
        var locked=productSalesLineMapper.selectByIdForUpdate(id);
        if(locked==null||oqcId==null||!Objects.equals(parent.getId(),locked.getSalesId())||!Objects.equals(parent.getStatus(),1)
                ||!Boolean.TRUE.equals(locked.getOqcCheckFlag())||!Objects.equals(locked.getQualityStatus(),MesWmQualityStatusEnum.PENDING.getStatus())
                ||(!Objects.equals(checkResult,MesQcCheckResultEnum.PASS.getType())&&!Objects.equals(checkResult,MesQcCheckResultEnum.FAIL.getType())))throw exception(WM_PRODUCT_SALES_CANNOT_CONFIRM);
        fulfillmentService.validateSales(parent);
        // 1. 更新行的 oqcId + qualityStatus
        Integer qualityStatus = Objects.equals(checkResult, MesQcCheckResultEnum.PASS.getType())
                ? MesWmQualityStatusEnum.PASS.getStatus() : MesWmQualityStatusEnum.FAIL.getStatus();
        productSalesLineMapper.updateById(new MesWmProductSalesLineDO()
                .setId(id).setOqcId(oqcId).setQualityStatus(qualityStatus));

        // 2. 检查同一出库单下所有需要 OQC 检验的行
        MesWmProductSalesLineDO currentLine = productSalesLineMapper.selectByIdForUpdate(id);
        if (currentLine == null || currentLine.getSalesId() == null) {
            return;
        }
        Long salesId = currentLine.getSalesId();
        List<MesWmProductSalesLineDO> allLines = productSalesLineMapper.selectListBySalesIdForUpdate(salesId);

        // 2.1 检查是否有不合格行 → 取消出库单
        boolean hasNg = CollectionUtils.anyMatch(allLines,
                line -> Boolean.TRUE.equals(line.getOqcCheckFlag())
                        && Objects.equals(line.getQualityStatus(), MesWmQualityStatusEnum.FAIL.getStatus()));
        if (hasNg) {
            log.info("[updateProductSalesLineWhenOqcFinish][销售出库单({}) 存在质检不合格行，取消出库单]", salesId);
            productSalesService.cancelProductSales(salesId);
            return;
        }

        // 2.2 检查是否所有 OQC 行都已完成，自动流转 CONFIRMED → APPROVING
        boolean hasUnchecked = CollectionUtils.anyMatch(allLines,
                line -> Boolean.TRUE.equals(line.getOqcCheckFlag()) && line.getOqcId() == null);
        if (!hasUnchecked) {
            log.info("[updateProductSalesLineWhenOqcFinish][销售出库单({}) 所有 OQC 行检验完成，流转到待拣货]", salesId);
            productSalesService.confirmProductSales(salesId);
        }
    }

    @Override
    public void updateProductSalesLineQualityStatus(List<Long> ids, Integer qualityStatus) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        productSalesLineMapper.updateQualityStatusByIds(ids, qualityStatus);
    }

}

