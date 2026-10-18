package cn.iocoder.yudao.module.mes.service.wm.itemreceipt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.MesWmItemReceiptPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.MesWmItemReceiptSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.arrivalnotice.MesWmArrivalNoticeDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt.MesWmItemReceiptMapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmItemReceiptStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmTransactionTypeEnum;
import cn.iocoder.yudao.module.mes.service.md.vendor.MesMdVendorService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemBatchConfigService;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemBatchConfigDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcService;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 采购入库单 Service 实现类
 */
@Service
@Validated
public class MesWmItemReceiptServiceImpl implements MesWmItemReceiptService {

    @Resource
    private MesWmItemReceiptMapper itemReceiptMapper;

    @Resource
    private MesWmItemReceiptLineService itemReceiptLineService;
    @Resource
    private MesWmItemReceiptDetailService itemReceiptDetailService;
    @Resource
    @Lazy
    private MesWmArrivalNoticeService arrivalNoticeService;
    @Resource
    private MesMdVendorService vendorService;
    @Resource
    @Lazy
    private MesQcIqcService iqcService;
    @Resource
    private MesWmTransactionService wmTransactionService;
    @Resource
    private MesMdItemService itemService;
    @Resource
    private MesWmBatchService batchService;
    @Resource
    private MesMdItemBatchConfigService itemBatchConfigService;

    @Override
    public Long createItemReceipt(MesWmItemReceiptSaveReqVO createReqVO) {
        // 校验数据
        validateItemReceiptSaveData(createReqVO);

        // 插入
        MesWmItemReceiptDO receipt = BeanUtils.toBean(createReqVO, MesWmItemReceiptDO.class);
        receipt.setStatus(MesWmItemReceiptStatusEnum.PREPARE.getStatus());
        itemReceiptMapper.insert(receipt);
        return receipt.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateItemReceipt(MesWmItemReceiptSaveReqVO updateReqVO) {
        // 校验存在 + 草稿状态
        MesWmItemReceiptDO receipt = validateItemReceiptExistsAndDraft(updateReqVO.getId());
        // 头字段也参与批次生成。父单锁下读取最新行，防止创建行与修改头字段交叉执行。
        List<MesWmItemReceiptLineDO> lines = itemReceiptLineService
                .getItemReceiptLineListByReceiptIdForUpdate(receipt.getId());
        if (CollUtil.isNotEmpty(lines)
                && (!Objects.equals(receipt.getVendorId(), updateReqVO.getVendorId())
                || !Objects.equals(receipt.getReceiptDate(), updateReqVO.getReceiptDate())
                || !Objects.equals(receipt.getPurchaseOrderCode(), updateReqVO.getPurchaseOrderCode()))) {
            throw exception(WM_ITEM_RECEIPT_HEADER_HAS_LINE);
        }
        // 校验数据
        validateItemReceiptSaveData(updateReqVO);

        // 更新
        MesWmItemReceiptDO updateObj = BeanUtils.toBean(updateReqVO, MesWmItemReceiptDO.class);
        // 显式处理可选关联的清空；全局 NOT_NULL 更新策略会忽略 null。
        updateObj.setIqcId(null);
        itemReceiptMapper.update(updateObj, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesWmItemReceiptDO>()
                .eq(MesWmItemReceiptDO::getId, updateReqVO.getId())
                .set(MesWmItemReceiptDO::getIqcId, updateReqVO.getIqcId())
                .set(updateReqVO.getPurchaseOrderCode() == null, MesWmItemReceiptDO::getPurchaseOrderCode, null));
    }

    private void validateItemReceiptSaveData(MesWmItemReceiptSaveReqVO reqVO) {
        // 校验编码唯一
        validateCodeUnique(reqVO.getId(), reqVO.getCode());
        // 校验供应商存在
        vendorService.validateVendorExistsAndEnable(reqVO.getVendorId());
        // 校验到货通知单存在
        if (reqVO.getNoticeId() != null) {
            MesWmArrivalNoticeDO notice = arrivalNoticeService.validateArrivalNoticeReadyForReceipt(reqVO.getNoticeId());
            if (ObjUtil.notEqual(notice.getVendorId(), reqVO.getVendorId())) {
                throw exception(WM_ARRIVAL_NOTICE_VENDOR_MISMATCH);
            }
        }
        // 校验来料检验单存在
        if (reqVO.getIqcId() != null) {
            MesQcIqcDO iqc = iqcService.validateIqcExists(reqVO.getIqcId());
            if (ObjUtil.notEqual(iqc.getVendorId(), reqVO.getVendorId())) {
                throw exception(WM_ITEM_RECEIPT_IQC_VENDOR_MISMATCH);
            }
            if (ObjUtil.notEqual(iqc.getStatus(), MesQcStatusEnum.FINISHED.getStatus())) {
                throw exception(WM_ITEM_RECEIPT_IQC_NOT_FINISHED);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItemReceipt(Long id) {
        // 校验存在 + 草稿状态
        validateItemReceiptExistsAndDraft(id);

        // 级联删除明细和行
        itemReceiptDetailService.deleteItemReceiptDetailByReceiptId(id);
        itemReceiptLineService.deleteItemReceiptLineByReceiptId(id);
        // 删除
        itemReceiptMapper.deleteById(id);
    }

    @Override
    public MesWmItemReceiptDO getItemReceipt(Long id) {
        return itemReceiptMapper.selectById(id);
    }

    @Override
    public PageResult<MesWmItemReceiptDO> getItemReceiptPage(MesWmItemReceiptPageReqVO pageReqVO) {
        return itemReceiptMapper.selectPage(pageReqVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitItemReceipt(Long id) {
        // 校验存在 + 草稿状态
        validateItemReceiptExistsAndDraft(id);
        // 校验至少有一条行
        List<MesWmItemReceiptLineDO> lines = itemReceiptLineService.getItemReceiptLineListByReceiptId(id);
        if (CollUtil.isEmpty(lines)) {
            throw exception(WM_ITEM_RECEIPT_NO_LINE);
        }

        // 提交（草稿 → 待上架）
        itemReceiptMapper.updateById(new MesWmItemReceiptDO()
                .setId(id).setStatus(MesWmItemReceiptStatusEnum.APPROVING.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stockItemReceipt(Long id) {
        // 校验存在
        MesWmItemReceiptDO receipt = validateItemReceiptExists(id);
        if (ObjUtil.notEqual(MesWmItemReceiptStatusEnum.APPROVING.getStatus(), receipt.getStatus())) {
            throw exception(WM_ITEM_RECEIPT_STATUS_ERROR);
        }
        validateFinalQuantities(receipt);

        // 执行上架（待上架 → 待入库）
        itemReceiptMapper.updateById(new MesWmItemReceiptDO()
                .setId(id).setStatus(MesWmItemReceiptStatusEnum.APPROVED.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishItemReceipt(Long id) {
        // 1. 校验存在
        MesWmItemReceiptDO receipt = validateItemReceiptExists(id);
        if (ObjUtil.notEqual(MesWmItemReceiptStatusEnum.APPROVED.getStatus(), receipt.getStatus())) {
            throw exception(WM_ITEM_RECEIPT_STATUS_ERROR);
        }

        List<MesWmItemReceiptDetailDO> details = validateFinalQuantities(receipt);

        // 2. 遍历所有明细，创建库存事务（增加库存 + 记录流水）
        createTransactionList(receipt, details);

        // 3. 更新入库单状态
        itemReceiptMapper.updateById(new MesWmItemReceiptDO()
                .setId(id).setStatus(MesWmItemReceiptStatusEnum.FINISHED.getStatus()));

        // 4. 更新关联的到货通知单状态
        if (receipt.getNoticeId() != null) {
            arrivalNoticeService.finishArrivalNotice(receipt.getNoticeId());
        }
    }

    private void createTransactionList(MesWmItemReceiptDO receipt, List<MesWmItemReceiptDetailDO> details) {
        wmTransactionService.createTransactionList(convertList(details, detail -> new MesWmTransactionSaveReqDTO()
                .setType(MesWmTransactionTypeEnum.IN.getType()).setItemId(detail.getItemId())
                .setQuantity(detail.getQuantity()) // 入库数量为正数
                .setBatchId(detail.getBatchId())
                .setWarehouseId(detail.getWarehouseId()).setLocationId(detail.getLocationId()).setAreaId(detail.getAreaId())
                .setVendorId(receipt.getVendorId()).setReceiptTime(receipt.getReceiptDate())
                .setBizType(MesBizTypeConstants.WM_ITEM_RECEIPT_IN).setBizId(receipt.getId())
                .setBizCode(receipt.getCode()).setBizLineId(detail.getLineId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelItemReceipt(Long id) {
        // 校验存在
        MesWmItemReceiptDO receipt = validateItemReceiptExists(id);
        // 已完成和已取消不允许取消
        if (ObjectUtils.equalsAny(receipt.getStatus(),
                MesWmItemReceiptStatusEnum.FINISHED.getStatus(),
                MesWmItemReceiptStatusEnum.CANCELED.getStatus())) {
            throw exception(WM_ITEM_RECEIPT_CANCEL_NOT_ALLOWED);
        }
        // 取消
        itemReceiptMapper.updateById(new MesWmItemReceiptDO()
                .setId(id).setStatus(MesWmItemReceiptStatusEnum.CANCELED.getStatus()));
    }

    @Override
    public MesWmItemReceiptDO validateItemReceiptEditable(Long id) {
        MesWmItemReceiptDO receipt = validateItemReceiptExists(id);
        if (ObjUtil.notEqual(receipt.getStatus(), MesWmItemReceiptStatusEnum.PREPARE.getStatus())
                && ObjUtil.notEqual(receipt.getStatus(), MesWmItemReceiptStatusEnum.APPROVING.getStatus())) {
            throw exception(WM_ITEM_RECEIPT_STATUS_NOT_PREPARE);
        }
        return receipt;
    }

    private MesWmItemReceiptDO validateItemReceiptExists(Long id) {
        MesWmItemReceiptDO receipt = itemReceiptMapper.selectForUpdate(id);
        if (receipt == null) {
            throw exception(WM_ITEM_RECEIPT_NOT_EXISTS);
        }
        return receipt;
    }

    /**
     * 校验采购入库单存在且为草稿状态
     */
    private MesWmItemReceiptDO validateItemReceiptExistsAndDraft(Long id) {
        MesWmItemReceiptDO receipt = validateItemReceiptExists(id);
        if (ObjUtil.notEqual(MesWmItemReceiptStatusEnum.PREPARE.getStatus(), receipt.getStatus())) {
            throw exception(WM_ITEM_RECEIPT_STATUS_NOT_PREPARE);
        }
        return receipt;
    }

    private void validateCodeUnique(Long id, String code) {
        MesWmItemReceiptDO receipt = itemReceiptMapper.selectByCode(code);
        if (receipt == null) {
            return;
        }
        if (ObjUtil.notEqual(id, receipt.getId())) {
            throw exception(WM_ITEM_RECEIPT_CODE_DUPLICATE);
        }
    }

    @Override
    public Long getItemReceiptCountByVendorId(Long vendorId) {
        return itemReceiptMapper.selectCountByVendorId(vendorId);
    }

    @Override
    public List<MesWmItemReceiptDO> getItemReceiptListByVendorId(Long vendorId) {
        return itemReceiptMapper.selectListByVendorId(vendorId);
    }

    @Override
    public List<MesWmItemReceiptDO> getItemReceiptList(Collection<Long> ids) {
        return itemReceiptMapper.selectByIds(ids);
    }

    /** 父单据锁下的最终防线：只使用当前行及明细，校验后直接将同一列表记账。 */
    private List<MesWmItemReceiptDetailDO> validateFinalQuantities(MesWmItemReceiptDO receipt) {
        Long id = receipt.getId();
        List<MesWmItemReceiptLineDO> lines = itemReceiptLineService.getItemReceiptLineListByReceiptIdForUpdate(id);
        if (CollUtil.isEmpty(lines)) {
            throw exception(WM_ITEM_RECEIPT_DETAIL_QUANTITY_MISMATCH);
        }
        List<MesWmItemReceiptDetailDO> details = itemReceiptDetailService.getItemReceiptDetailListByReceiptIdForUpdate(id);
        Map<Long, MesWmItemReceiptLineDO> byId = new HashMap<>();
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (MesWmItemReceiptLineDO line : lines) {
            byId.put(line.getId(), line);
        }
        for (MesWmItemReceiptDetailDO detail : details) {
            MesWmItemReceiptLineDO line = byId.get(detail.getLineId());
            if (line == null || !Objects.equals(detail.getReceiptId(), id)
                    || !Objects.equals(line.getReceiptId(), id)
                    || !Objects.equals(line.getItemId(), detail.getItemId())
                    || detail.getQuantity() == null || detail.getQuantity().signum() <= 0) {
                throw exception(WM_ITEM_RECEIPT_DETAIL_QUANTITY_MISMATCH);
            }
            if (!Objects.equals(line.getBatchId(), detail.getBatchId())) {
                throw exception(WM_ITEM_RECEIPT_DETAIL_BATCH_MISMATCH);
            }
            totals.merge(line.getId(), detail.getQuantity(), BigDecimal::add);
        }
        for (MesWmItemReceiptLineDO line : lines) {
            if (line.getReceivedQuantity() == null || line.getReceivedQuantity().signum() <= 0
                    || line.getReceivedQuantity().compareTo(totals.getOrDefault(line.getId(), BigDecimal.ZERO)) != 0) {
                throw exception(WM_ITEM_RECEIPT_DETAIL_QUANTITY_MISMATCH);
            }
            var item = itemService.validateItemExists(line.getItemId());
            boolean batchManaged = !Boolean.FALSE.equals(item.getBatchFlag());
            if (batchManaged && line.getBatchId() == null) {
                throw exception(MD_ITEM_BATCH_REQUIRED);
            }
            if (line.getBatchId() != null) {
                MesWmBatchDO batch = batchService.validateBatchExists(line.getBatchId(), line.getItemId());
                if (batchManaged) {
                    validateBatchAttributes(receipt, line, batch);
                }
            }
        }
        return details;
    }

    /** 只读核验历史数据，不能通过生成新批次掩盖已有上架实物的批次漂移。 */
    private void validateBatchAttributes(MesWmItemReceiptDO receipt, MesWmItemReceiptLineDO line, MesWmBatchDO batch) {
        MesMdItemBatchConfigDO config = itemBatchConfigService.validateItemBatchConfigExists(line.getItemId());
        if (!batchAttributeMatches(config.getVendorFlag(), receipt.getVendorId(), batch.getVendorId())
                || !batchAttributeMatches(config.getReceiptDateFlag(), receipt.getReceiptDate(), batch.getReceiptDate())
                || !batchAttributeMatches(config.getPurchaseOrderCodeFlag(), receipt.getPurchaseOrderCode(), batch.getPurchaseOrderCode())
                || !batchAttributeMatches(config.getProduceDateFlag(), line.getProductionDate(), batch.getProduceDate())
                || !batchAttributeMatches(config.getExpireDateFlag(), line.getExpireDate(), batch.getExpireDate())
                || !batchAttributeMatches(config.getLotNumberFlag(), line.getLotNumber(), batch.getLotNumber())) {
            throw exception(WM_ITEM_RECEIPT_BATCH_ATTRIBUTES_MISMATCH);
        }
    }

    private boolean batchAttributeMatches(Boolean enabled, Object expected, Object actual) {
        return !Boolean.TRUE.equals(enabled) || expected != null && Objects.equals(expected, actual);
    }
}
