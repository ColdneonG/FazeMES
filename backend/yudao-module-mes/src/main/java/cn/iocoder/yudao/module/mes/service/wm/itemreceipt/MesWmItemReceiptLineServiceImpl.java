package cn.iocoder.yudao.module.mes.service.wm.itemreceipt;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.batch.vo.MesWmBatchGenerateReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.line.MesWmItemReceiptLinePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.itemreceipt.vo.line.MesWmItemReceiptLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt.MesWmItemReceiptLineMapper;
import cn.iocoder.yudao.module.mes.service.wm.arrivalnotice.MesWmArrivalNoticeLineService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 采购入库单行 Service 实现类
 */
@Service
@Validated
public class MesWmItemReceiptLineServiceImpl implements MesWmItemReceiptLineService {

    @Resource
    private MesWmItemReceiptLineMapper itemReceiptLineMapper;

    @Resource
    @Lazy
    private MesWmItemReceiptService itemReceiptService;
    @Resource
    private MesWmItemReceiptDetailService itemReceiptDetailService;
    @Resource
    @Lazy
    private MesWmArrivalNoticeLineService arrivalNoticeLineService;
    @Resource
    private MesWmBatchService batchService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createItemReceiptLine(MesWmItemReceiptLineSaveReqVO createReqVO) {
        // 1. 校验父单据存在且为可编辑状态
        MesWmItemReceiptDO receipt = itemReceiptService.validateItemReceiptEditable(createReqVO.getReceiptId());
        // 2. 校验数据
        validateItemReceiptLineSaveData(receipt, createReqVO);

        // 3.1 创建入库单行
        MesWmItemReceiptLineDO line = BeanUtils.toBean(createReqVO, MesWmItemReceiptLineDO.class);
        // 3.2 生成或获取批次
        MesWmBatchDO batch = generateOrGetBatch(receipt, createReqVO);
        if (batch != null) {
            line.setBatchId(batch.getId());
            line.setBatchCode(batch.getCode());
        }
        // 3.3 插入数据库
        itemReceiptLineMapper.insert(line);
        return line.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateItemReceiptLine(MesWmItemReceiptLineSaveReqVO updateReqVO) {
        // 锁前只用持久化的父编号定位单据，禁止客户端将行移出已关闭单据。
        MesWmItemReceiptLineDO persistedParent = validateItemReceiptLineExists(updateReqVO.getId());
        updateReqVO.setReceiptId(persistedParent.getReceiptId());
        MesWmItemReceiptDO receipt = itemReceiptService.validateItemReceiptEditable(persistedParent.getReceiptId());
        // 等待父单锁时行可能已被修改或删除，必须使用当前读重新校验。
        MesWmItemReceiptLineDO line = itemReceiptLineMapper.selectForUpdate(updateReqVO.getId());
        if (line == null) {
            throw exception(WM_ITEM_RECEIPT_LINE_NOT_EXISTS);
        }
        if (!Objects.equals(line.getReceiptId(), receipt.getId())) {
            throw exception(WM_ITEM_RECEIPT_DETAIL_LINE_MISMATCH);
        }
        validateItemReceiptLineSaveData(receipt, updateReqVO);

        MesWmItemReceiptLineDO updateObj = BeanUtils.toBean(updateReqVO, MesWmItemReceiptLineDO.class);
        var details = itemReceiptDetailService.getItemReceiptDetailListByLineIdForUpdate(line.getId());
        if (CollUtil.isNotEmpty(details)) {
            // 已分配实物的物料和批次属性不可静默替换，删掉上架明细后才可重新分配。
            if (!Objects.equals(line.getItemId(), updateReqVO.getItemId())
                    || !Objects.equals(line.getProductionDate(), updateReqVO.getProductionDate())
                    || !Objects.equals(line.getExpireDate(), updateReqVO.getExpireDate())
                    || !Objects.equals(line.getLotNumber(), updateReqVO.getLotNumber())) {
                throw exception(WM_ITEM_RECEIPT_LINE_HAS_DETAIL);
            }
            BigDecimal total = BigDecimal.ZERO;
            for (var detail : details) {
                if (!Objects.equals(detail.getReceiptId(), receipt.getId())
                        || !Objects.equals(detail.getItemId(), line.getItemId())) {
                    throw exception(WM_ITEM_RECEIPT_DETAIL_LINE_MISMATCH);
                }
                if (!Objects.equals(detail.getBatchId(), line.getBatchId())) {
                    throw exception(WM_ITEM_RECEIPT_DETAIL_BATCH_MISMATCH);
                }
                if (detail.getQuantity() == null || detail.getQuantity().signum() <= 0) {
                    throw exception(WM_ITEM_RECEIPT_DETAIL_QUANTITY_MISMATCH);
                }
                total = total.add(detail.getQuantity());
            }
            if (updateReqVO.getReceivedQuantity() == null || updateReqVO.getReceivedQuantity().compareTo(total) < 0) {
                throw exception(WM_ITEM_RECEIPT_DETAIL_QUANTITY_EXCEED);
            }
            // 数量和备注的正常编辑、重试不重新生成批次。
            updateObj.setBatchId(line.getBatchId()).setBatchCode(line.getBatchCode());
        } else {
            MesWmBatchDO batch = generateOrGetBatch(receipt, updateReqVO);
            updateObj.setBatchId(batch == null ? null : batch.getId())
                    .setBatchCode(batch == null ? null : batch.getCode());
        }
        // 显式写入可空字段，避免更换为无批次物料或清空日期后残留旧值。
        itemReceiptLineMapper.update(updateObj, new LambdaUpdateWrapper<MesWmItemReceiptLineDO>()
                .eq(MesWmItemReceiptLineDO::getId, line.getId())
                .set(updateObj.getBatchId() == null, MesWmItemReceiptLineDO::getBatchId, null)
                .set(updateObj.getBatchCode() == null, MesWmItemReceiptLineDO::getBatchCode, null)
                .set(updateReqVO.getProductionDate() == null, MesWmItemReceiptLineDO::getProductionDate, null)
                .set(updateReqVO.getExpireDate() == null, MesWmItemReceiptLineDO::getExpireDate, null)
                .set(updateReqVO.getLotNumber() == null, MesWmItemReceiptLineDO::getLotNumber, null)
                .set(updateReqVO.getArrivalNoticeLineId() == null, MesWmItemReceiptLineDO::getArrivalNoticeLineId, null));
    }

    private void validateItemReceiptLineSaveData(MesWmItemReceiptDO receipt, MesWmItemReceiptLineSaveReqVO reqVO) {
        // 校验关联到货通知单行
        validateArrivalNoticeLine(receipt, reqVO.getArrivalNoticeLineId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItemReceiptLine(Long id) {
        // 1.1 校验存在
        MesWmItemReceiptLineDO line = validateItemReceiptLineExists(id);
        // 1.2 校验父单据存在且为可编辑状态
        itemReceiptService.validateItemReceiptEditable(line.getReceiptId());

        // 2.1 级联删除明细
        itemReceiptDetailService.deleteItemReceiptDetailByLineId(id);
        // 2.2 删除
        itemReceiptLineMapper.deleteById(id);
    }

    @Override
    public MesWmItemReceiptLineDO getItemReceiptLine(Long id) {
        return itemReceiptLineMapper.selectById(id);
    }

    @Override
    public MesWmItemReceiptLineDO getItemReceiptLineForUpdate(Long id) {
        return itemReceiptLineMapper.selectForUpdate(id);
    }

    @Override
    public List<MesWmItemReceiptLineDO> getItemReceiptLineListByReceiptIdForUpdate(Long receiptId) {
        return itemReceiptLineMapper.selectListByReceiptIdForUpdate(receiptId);
    }

    @Override
    public PageResult<MesWmItemReceiptLineDO> getItemReceiptLinePage(MesWmItemReceiptLinePageReqVO pageReqVO) {
        // 如果传了 vendorId，先查出该供应商的所有入库单 ID，设置到 receiptIds
        if (pageReqVO.getReceiptId() == null) {
            List<MesWmItemReceiptDO> receipts = itemReceiptService.getItemReceiptListByVendorId(pageReqVO.getVendorId());
            if (CollUtil.isEmpty(receipts)) {
                return PageResult.empty();
            }
            pageReqVO.setReceiptIds(convertList(receipts, MesWmItemReceiptDO::getId));
        }
        // 查询分页
        return itemReceiptLineMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesWmItemReceiptLineDO> getItemReceiptLineListByReceiptId(Long receiptId) {
        return itemReceiptLineMapper.selectListByReceiptId(receiptId);
    }

    @Override
    public void deleteItemReceiptLineByReceiptId(Long receiptId) {
        itemReceiptLineMapper.deleteByReceiptId(receiptId);
    }

    /**
     * 校验到货通知单行
     *
     * @param receipt 入库单
     * @param arrivalNoticeLineId 到货通知单行编号
     */
    private void validateArrivalNoticeLine(MesWmItemReceiptDO receipt, Long arrivalNoticeLineId) {
        // 情况一：如果入库单关联了到货通知单，则必须关联到货通知单行
        if (receipt.getNoticeId() != null) {
            if (arrivalNoticeLineId == null) {
                throw exception(WM_ITEM_RECEIPT_LINE_ARRIVAL_NOTICE_LINE_REQUIRED);
            }
            arrivalNoticeLineService.validateArrivalNoticeLineExists(
                    arrivalNoticeLineId, receipt.getNoticeId());
            return;
        }

        // 情况二：如果入库单没有关联到货通知单，则不允许关联到货通知单行
        if (arrivalNoticeLineId != null) {
            throw exception(WM_ITEM_RECEIPT_LINE_ARRIVAL_NOTICE_LINE_NOT_ALLOWED);
        }
    }

    private MesWmItemReceiptLineDO validateItemReceiptLineExists(Long id) {
        MesWmItemReceiptLineDO line = itemReceiptLineMapper.selectById(id);
        if (line == null) {
            throw exception(WM_ITEM_RECEIPT_LINE_NOT_EXISTS);
        }
        return line;
    }

    /**
     * 生成或获取批次
     *
     * @param receipt 入库单
     * @param reqVO 入库单行请求VO
     * @return 批次记录（如果物料未启用批次管理则返回 null）
     */
    private MesWmBatchDO generateOrGetBatch(MesWmItemReceiptDO receipt, MesWmItemReceiptLineSaveReqVO reqVO) {
        // 构建批次参数 VO
        MesWmBatchGenerateReqVO batchReqVO = new MesWmBatchGenerateReqVO();
        // 从入库单行获取
        batchReqVO.setItemId(reqVO.getItemId()).setProduceDate(reqVO.getProductionDate())
                .setExpireDate(reqVO.getExpireDate()).setLotNumber(reqVO.getLotNumber());
        // 从父单据获取
        batchReqVO.setVendorId(receipt.getVendorId()).setReceiptDate(receipt.getReceiptDate())
                .setPurchaseOrderCode(receipt.getPurchaseOrderCode());

        // 生成或获取批次
        return batchService.getOrGenerateBatchCode(batchReqVO);
    }

}
