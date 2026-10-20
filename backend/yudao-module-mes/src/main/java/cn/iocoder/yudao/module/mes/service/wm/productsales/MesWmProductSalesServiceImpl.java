package cn.iocoder.yudao.module.mes.service.wm.productsales;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productsales.vo.MesWmProductSalesShippingReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.salesnotice.MesWmSalesNoticeDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesMapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductSalesStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmSalesNoticeStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmTransactionTypeEnum;
import cn.iocoder.yudao.module.mes.service.md.client.MesMdClientService;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcService;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 销售出库单 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesWmProductSalesServiceImpl implements MesWmProductSalesService {

    @Resource
    private MesWmProductSalesMapper productSalesMapper;

    @Resource
    private MesWmProductSalesLineService productSalesLineService;
    @Resource
    private MesWmProductSalesDetailService productSalesDetailService;
    @Resource
    private MesMdClientService clientService;
    @Resource
    private MesWmSalesNoticeService salesNoticeService;
    @Resource
    @Lazy
    private MesQcOqcService oqcService;
    @Resource
    private MesWmTransactionService wmTransactionService;
    @Resource private cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeFulfillmentService fulfillmentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProductSales(MesWmProductSalesSaveReqVO createReqVO) {
        // 校验数据
        validateProductSalesSaveData(null, createReqVO);

        // 插入
        MesWmProductSalesDO sales = BeanUtils.toBean(createReqVO, MesWmProductSalesDO.class);
        sales.setStatus(MesWmProductSalesStatusEnum.PREPARE.getStatus());
        fulfillmentService.validateSales(sales);
        productSalesMapper.insert(sales);
        return sales.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateProductSales(MesWmProductSalesSaveReqVO updateReqVO) {
        // 校验存在 + 草稿状态
        var old=fulfillmentService.lockSalesForUpdate(updateReqVO.getId(),updateReqVO.getNoticeId());
        if(!ObjUtil.equal(old.getStatus(),MesWmProductSalesStatusEnum.PREPARE.getStatus()))throw exception(WM_PRODUCT_SALES_NOT_PREPARE);
        fulfillmentService.requireNoPosting(old.getId());
        if((!java.util.Objects.equals(old.getNoticeId(),updateReqVO.getNoticeId())||!java.util.Objects.equals(old.getClientId(),updateReqVO.getClientId()))
                &&!productSalesLineService.getProductSalesLineListBySalesId(old.getId()).isEmpty())
            throw exception(WM_SALES_NOTICE_FULFILLMENT_INVALID,"已有出库行时不能变更客户或发货通知，请先移除草稿行");
        // 校验数据
        validateProductSalesSaveData(updateReqVO.getId(), updateReqVO);

        // 更新
        MesWmProductSalesDO updateObj = BeanUtils.toBean(updateReqVO, MesWmProductSalesDO.class);
        // 显式处理可选参考关联清空，状态更新仍保留原有参考关联。
        updateObj.setOqcId(null).setNoticeId(null);
        productSalesMapper.update(updateObj, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesWmProductSalesDO>()
                .eq(MesWmProductSalesDO::getId, updateReqVO.getId()).set(MesWmProductSalesDO::getOqcId, updateReqVO.getOqcId()).set(MesWmProductSalesDO::getNoticeId,updateReqVO.getNoticeId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductSales(Long id) {
        // 校验存在 + 草稿状态
        validateProductSalesExistsAndDraft(id);
        fulfillmentService.requireNoPosting(id);

        // 级联删除明细和行
        productSalesDetailService.deleteProductSalesDetailBySalesId(id);
        productSalesLineService.deleteProductSalesLineBySalesId(id);
        // 删除
        productSalesMapper.deleteById(id);
    }

    @Override
    public MesWmProductSalesDO getProductSales(Long id) {
        return productSalesMapper.selectById(id);
    }

    @Override
    public PageResult<MesWmProductSalesDO> getProductSalesPage(MesWmProductSalesPageReqVO pageReqVO) {
        return productSalesMapper.selectPage(pageReqVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitProductSales(Long id) {
        // 校验存在 + 草稿状态
        var sales=validateProductSalesExistsAndDraft(id);
        fulfillmentService.validateSales(sales);
        // 校验至少有一条行
        List<MesWmProductSalesLineDO> lines = productSalesLineService.getProductSalesLineListBySalesId(id);
        if (CollUtil.isEmpty(lines)) {
            throw exception(WM_PRODUCT_SALES_LINES_EMPTY);
        }
        // 校验每行出库数量 > 0
        for (MesWmProductSalesLineDO line : lines) {
            if (line.getQuantity() == null || BigDecimal.ZERO.compareTo(line.getQuantity()) >= 0) {
                throw exception(WM_PRODUCT_SALES_LINE_QUANTITY_INVALID);
            }
        }

        // 检查所有行的 oqcCheckFlag：如果有需要 OQC 检验的行，进入待检测状态
        boolean needOqc = CollectionUtils.anyMatch(lines,
                line -> Boolean.TRUE.equals(line.getOqcCheckFlag()));
        if (needOqc) {
            // 批量初始化 OQC 行的质量状态为"待检验"
            List<Long> oqcLineIds = convertList(
                    CollectionUtils.filterList(lines, line -> Boolean.TRUE.equals(line.getOqcCheckFlag())),
                    MesWmProductSalesLineDO::getId);
            productSalesLineService.updateProductSalesLineQualityStatus(oqcLineIds, MesWmQualityStatusEnum.PENDING.getStatus());
            // 需要检验，进入待检测
            productSalesMapper.updateById(new MesWmProductSalesDO()
                    .setId(id).setStatus(MesWmProductSalesStatusEnum.CONFIRMED.getStatus()));
            return;
        }
        // 不需要检验，直接进入待拣货
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(id).setStatus(MesWmProductSalesStatusEnum.APPROVING.getStatus()));
    }

    @Override
    public boolean checkProductSalesQuantity(Long id) {
        // 校验每行明细数量之和是否等于行出库数量
        List<MesWmProductSalesLineDO> lines = productSalesLineService.getProductSalesLineListBySalesId(id);
        for (MesWmProductSalesLineDO line : lines) {
            List<MesWmProductSalesDetailDO> details = productSalesDetailService.getProductSalesDetailListByLineId(line.getId());
            if (CollUtil.isEmpty(details)) {
                return false;
            }
            BigDecimal totalDetailQty = CollectionUtils.getSumValue(details,
                    MesWmProductSalesDetailDO::getQuantity, BigDecimal::add, BigDecimal.ZERO);
            if (line.getQuantity() != null && totalDetailQty.compareTo(line.getQuantity()) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stockProductSales(Long id) {
        // 校验存在
        MesWmProductSalesDO sales = validateProductSalesExists(id);
        if (ObjUtil.notEqual(MesWmProductSalesStatusEnum.APPROVING.getStatus(), sales.getStatus())) {
            throw exception(WM_PRODUCT_SALES_CANNOT_PICK);
        }
        // The final current-read invariant also checks that every line has all required picking details.
        validateFinalQuantities(id);
        fulfillmentService.validateSales(sales);

        // 执行拣货（待拣货 → 待填写运单）
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(id).setStatus(MesWmProductSalesStatusEnum.SHIPPING.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shippingProductSales(MesWmProductSalesShippingReqVO reqVO) {
        // 校验存在
        MesWmProductSalesDO sales = validateProductSalesExists(reqVO.getId());
        if (ObjUtil.notEqual(MesWmProductSalesStatusEnum.SHIPPING.getStatus(), sales.getStatus())) {
            throw exception(WM_PRODUCT_SALES_CANNOT_SHIPPING);
        }
        fulfillmentService.validateSales(sales);

        // 填写运单（待填写运单 → 待执行出库）
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(reqVO.getId())
                .setCarrier(reqVO.getCarrier())
                .setShippingNumber(reqVO.getShippingNumber())
                .setStatus(MesWmProductSalesStatusEnum.APPROVED.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishProductSales(Long id) {
        // 1. 校验存在
        MesWmProductSalesDO sales = validateProductSalesExists(id);
        if (ObjUtil.notEqual(MesWmProductSalesStatusEnum.APPROVED.getStatus(), sales.getStatus())) {
            throw exception(WM_PRODUCT_SALES_CANNOT_FINISH);
        }

        var verifiedDetails=validateFinalQuantities(id);
        fulfillmentService.validateSales(sales);

        // Post exactly the current rows that were verified under the parent lock.
        createTransactionList(sales,verifiedDetails);

        // 3. 更新出库单状态
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(id).setStatus(MesWmProductSalesStatusEnum.FINISHED.getStatus()));
        fulfillmentService.refreshNotice(sales.getNoticeId());
    }

    private void createTransactionList(MesWmProductSalesDO sales,List<MesWmProductSalesDetailDO> details) {
        wmTransactionService.createTransactionList(convertList(details, detail -> new MesWmTransactionSaveReqDTO()
                .setType(MesWmTransactionTypeEnum.OUT.getType()).setItemId(detail.getItemId())
                .setMaterialStockId(detail.getMaterialStockId())
                .setQuantity(detail.getQuantity().negate()) // 出库数量为负数
                .setBatchId(detail.getBatchId()).setBatchCode(detail.getBatchCode())
                .setWarehouseId(detail.getWarehouseId()).setLocationId(detail.getLocationId()).setAreaId(detail.getAreaId())
                .setBizType(MesBizTypeConstants.WM_PRODUCT_SALES).setBizId(sales.getId())
                .setBizCode(sales.getCode()).setBizLineId(detail.getLineId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelProductSales(Long id) {
        // 校验存在
        MesWmProductSalesDO sales = validateProductSalesExists(id);
        fulfillmentService.requireNoPosting(id);
        // 已完成和已取消不允许取消
        if (ObjectUtils.equalsAny(sales.getStatus(),
                MesWmProductSalesStatusEnum.FINISHED.getStatus(),
                MesWmProductSalesStatusEnum.CANCELED.getStatus())) {
            throw exception(WM_PRODUCT_SALES_CANNOT_CANCEL);
        }
        // 取消
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(id).setStatus(MesWmProductSalesStatusEnum.CANCELED.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmProductSales(Long id) {
        // 校验存在 + 待检测状态
        MesWmProductSalesDO sales = validateProductSalesExists(id);
        if (ObjUtil.notEqual(MesWmProductSalesStatusEnum.CONFIRMED.getStatus(), sales.getStatus())) {
            throw exception(WM_PRODUCT_SALES_CANNOT_CONFIRM);
        }
        fulfillmentService.validateSales(sales);
        validateOqc(productSalesLineService.getProductSalesLineListBySalesId(id));
        // OQC 检验完成，流转到待拣货
        productSalesMapper.updateById(new MesWmProductSalesDO()
                .setId(id).setStatus(MesWmProductSalesStatusEnum.APPROVING.getStatus()));
    }

    @Override
    public List<MesWmProductSalesDO> getProductSalesListByClientId(Long clientId) {
        return productSalesMapper.selectListByClientId(clientId);
    }

    private void validateProductSalesSaveData(Long id, MesWmProductSalesSaveReqVO reqVO) {
        // 校验编码唯一
        validateCodeUnique(id, reqVO.getCode());
        // 校验客户存在
        clientService.validateClientExistsAndEnable(reqVO.getClientId());
        if (reqVO.getOqcId() != null) {
            MesQcOqcDO oqc = oqcService.validateOqcExists(reqVO.getOqcId());
            if (ObjUtil.notEqual(oqc.getClientId(), reqVO.getClientId())) {
                throw exception(WM_PRODUCT_SALES_OQC_CLIENT_MISMATCH);
            }
            if (ObjUtil.notEqual(oqc.getStatus(), MesQcStatusEnum.FINISHED.getStatus())) {
                throw exception(WM_PRODUCT_SALES_OQC_NOT_FINISHED);
            }
        }
        // 校验发货通知单
        if (reqVO.getNoticeId() != null) {
            fulfillmentService.requireOpenNotice(reqVO.getNoticeId(),reqVO.getClientId());
            MesWmSalesNoticeDO notice = salesNoticeService.validateSalesNoticeExists(reqVO.getNoticeId());
            if (ObjUtil.notEqual(notice.getStatus(), MesWmSalesNoticeStatusEnum.APPROVED.getStatus())) {
                throw exception(WM_SALES_NOTICE_STATUS_NOT_APPROVED);
            }
            if (ObjUtil.notEqual(notice.getClientId(), reqVO.getClientId())) {
                throw exception(WM_SALES_NOTICE_CLIENT_MISMATCH);
            }
        }
    }

    private MesWmProductSalesDO validateProductSalesExists(Long id) {
        MesWmProductSalesDO sales = fulfillmentService.lockSales(id);
        if (sales == null) {
            throw exception(WM_PRODUCT_SALES_NOT_EXISTS);
        }
        if(!java.util.Objects.equals(sales.getStatus(),4))fulfillmentService.requireNoPosting(id);
        return sales;
    }

    /**
     * 校验销售出库单存在且为草稿状态
     */
    @Override
    public MesWmProductSalesDO validateProductSalesExistsAndDraft(Long id) {
        MesWmProductSalesDO sales = validateProductSalesExists(id);
        if (ObjUtil.notEqual(MesWmProductSalesStatusEnum.PREPARE.getStatus(), sales.getStatus())) {
            throw exception(WM_PRODUCT_SALES_NOT_PREPARE);
        }
        fulfillmentService.requireNoPosting(id);
        return sales;
    }

    private void validateCodeUnique(Long id, String code) {
        MesWmProductSalesDO sales = productSalesMapper.selectByCode(code);
        if (sales == null) {
            return;
        }
        if (ObjUtil.notEqual(id, sales.getId())) {
            throw exception(WM_PRODUCT_SALES_CODE_DUPLICATE);
        }
    }

    /** Final invariant under the document lock, before any stock movement. */
    private List<MesWmProductSalesDetailDO> validateFinalQuantities(Long id) {
        var lines=productSalesLineService.getProductSalesLineListBySalesId(id);
        if(lines.isEmpty()) throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_PRODUCT_SALES_DETAIL_QUANTITY_MISMATCH);
        var details=productSalesDetailService.getProductSalesDetailListBySalesId(id);
        java.util.Map<Long,MesWmProductSalesLineDO> byId=new java.util.HashMap<>();
        java.util.Map<Long,java.math.BigDecimal> totals=new java.util.HashMap<>();
        validateOqc(lines);
        fulfillmentService.validateCompletedOqc(productSalesMapper.selectForUpdate(id),lines);
        for(var line:lines){if(!java.util.Objects.equals(line.getSalesId(),id))throw exception(WM_PRODUCT_SALES_DETAIL_QUANTITY_MISMATCH);fulfillmentService.requireQuantity(line.getQuantity());byId.put(line.getId(),line);}
        for(var detail:details) {
            var line=byId.get(detail.getLineId());
            if(line==null || !java.util.Objects.equals(detail.getSalesId(),id)
                || !java.util.Objects.equals(line.getItemId(),detail.getItemId())
                || line.getBatchId()!=null && !java.util.Objects.equals(line.getBatchId(),detail.getBatchId())
                || line.getBatchCode()!=null && !line.getBatchCode().isBlank() && !java.util.Objects.equals(line.getBatchCode(),detail.getBatchCode())
                || detail.getQuantity()==null || detail.getQuantity().signum()<=0)
                throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_PRODUCT_SALES_DETAIL_QUANTITY_MISMATCH);
            totals.merge(line.getId(),detail.getQuantity(),java.math.BigDecimal::add);
        }
        for(var line:lines) if(line.getQuantity()==null || line.getQuantity().signum()<=0
            || line.getQuantity().compareTo(totals.getOrDefault(line.getId(),java.math.BigDecimal.ZERO))!=0)
            throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_PRODUCT_SALES_DETAIL_QUANTITY_MISMATCH);
        return details;
    }

    private void validateOqc(List<MesWmProductSalesLineDO> lines){
        for(var line:lines)if(Boolean.TRUE.equals(line.getOqcCheckFlag())
                &&(!java.util.Objects.equals(line.getQualityStatus(),MesWmQualityStatusEnum.PASS.getStatus())||line.getOqcId()==null))
            throw exception(WM_PRODUCT_SALES_CANNOT_CONFIRM);
    }
}
