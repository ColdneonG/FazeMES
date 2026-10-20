package cn.iocoder.yudao.module.mes.service.wm.salesnotice;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.vo.line.MesWmSalesNoticeLinePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.salesnotice.vo.line.MesWmSalesNoticeLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.salesnotice.MesWmSalesNoticeDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.salesnotice.MesWmSalesNoticeLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.salesnotice.MesWmSalesNoticeLineMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.mes.enums.wm.MesWmSalesNoticeStatusEnum.PREPARE;

/**
 * MES 发货通知单行 Service 实现类
 */
@Service
@Validated
public class MesWmSalesNoticeLineServiceImpl implements MesWmSalesNoticeLineService {

    @Resource
    private MesWmSalesNoticeLineMapper salesNoticeLineMapper;

    @Resource
    @Lazy
    private MesWmSalesNoticeService salesNoticeService;
    @Resource
    private MesWmBatchService batchService;
    @Resource
    private MesMdItemService itemService;
    @Resource private MesWmSalesNoticeFulfillmentService fulfillmentService;

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public Long createSalesNoticeLine(MesWmSalesNoticeLineSaveReqVO createReqVO) {
        // 校验数据
        validateLineSaveData(createReqVO);

        // 插入
        MesWmSalesNoticeLineDO line = BeanUtils.toBean(createReqVO, MesWmSalesNoticeLineDO.class);
        fillBatchId(line);
        salesNoticeLineMapper.insert(line);
        return line.getId();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void updateSalesNoticeLine(MesWmSalesNoticeLineSaveReqVO updateReqVO) {
        // 校验存在
        MesWmSalesNoticeLineDO line = validateSalesNoticeLineExists(updateReqVO.getId());
        validateNoticeStatusDraft(line.getNoticeId());
        line=salesNoticeLineMapper.selectByIdForUpdate(updateReqVO.getId());
        if(line==null)throw exception(WM_SALES_NOTICE_LINE_NOT_EXISTS);
        if(updateReqVO.getNoticeId()!=null&&!java.util.Objects.equals(updateReqVO.getNoticeId(),line.getNoticeId()))throw exception(WM_SALES_NOTICE_LINE_NOT_MATCH);
        // 校验数据
        updateReqVO.setNoticeId(line.getNoticeId());
        validateLineSaveData(updateReqVO);

        // 更新
        MesWmSalesNoticeLineDO updateObj = BeanUtils.toBean(updateReqVO, MesWmSalesNoticeLineDO.class);
        fillBatchId(updateObj);
        salesNoticeLineMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesWmSalesNoticeLineDO>()
                .eq(MesWmSalesNoticeLineDO::getId,updateObj.getId()).set(MesWmSalesNoticeLineDO::getItemId,updateObj.getItemId())
                .set(MesWmSalesNoticeLineDO::getQuantity,updateObj.getQuantity()).set(MesWmSalesNoticeLineDO::getBatchId,updateObj.getBatchId())
                .set(MesWmSalesNoticeLineDO::getBatchCode,updateObj.getBatchCode()).set(MesWmSalesNoticeLineDO::getOqcCheckFlag,updateObj.getOqcCheckFlag()).set(MesWmSalesNoticeLineDO::getRemark,updateObj.getRemark()));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void deleteSalesNoticeLine(Long id) {
        // 校验存在
        MesWmSalesNoticeLineDO line = validateSalesNoticeLineExists(id);
        // 校验父单据存在且为草稿状态
        validateNoticeStatusDraft(line.getNoticeId());
        salesNoticeLineMapper.selectByIdForUpdate(id);

        // 删除
        salesNoticeLineMapper.deleteById(id);
    }

    @Override
    public MesWmSalesNoticeLineDO getSalesNoticeLine(Long id) {
        return salesNoticeLineMapper.selectById(id);
    }

    @Override
    public PageResult<MesWmSalesNoticeLineDO> getSalesNoticeLinePage(MesWmSalesNoticeLinePageReqVO pageReqVO) {
        return salesNoticeLineMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesWmSalesNoticeLineDO> getSalesNoticeLineListByNoticeId(Long noticeId) {
        return salesNoticeLineMapper.selectListByNoticeIdForUpdate(noticeId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void deleteSalesNoticeLineByNoticeId(Long noticeId) {
        validateNoticeStatusDraft(noticeId);
        salesNoticeLineMapper.deleteByNoticeId(noticeId);
    }

    @Override
    public MesWmSalesNoticeLineDO validateSalesNoticeLineExists(Long id) {
        MesWmSalesNoticeLineDO line = salesNoticeLineMapper.selectById(id);
        if (line == null) {
            throw exception(WM_SALES_NOTICE_LINE_NOT_EXISTS);
        }
        return line;
    }

    @Override
    public MesWmSalesNoticeLineDO validateSalesNoticeLineExists(Long lineId, Long noticeId) {
        MesWmSalesNoticeLineDO line = validateSalesNoticeLineExists(lineId);
        // 进一步校验行的 noticeId 与传入的 noticeId 是否匹配
        if (noticeId != null && ObjUtil.notEqual(line.getNoticeId(), noticeId)) {
            throw exception(WM_SALES_NOTICE_LINE_NOT_MATCH);
        }
        return line;
    }

    private void validateLineSaveData(MesWmSalesNoticeLineSaveReqVO reqVO) {
        // 校验父单据存在且为草稿状态
        validateNoticeStatusDraft(reqVO.getNoticeId());
        fulfillmentService.requireQuantity(reqVO.getQuantity());
        // 校验物料存在
        if (reqVO.getItemId() != null) {
            itemService.validateItemExistsAndEnable(reqVO.getItemId());
        }
    }

    /**
     * 校验父发货通知单存在且为草稿状态
     */
    private void validateNoticeStatusDraft(Long noticeId) {
        fulfillmentService.requireDraftNoticeWithoutSales(noticeId);
        var row=fulfillmentService.lockNotice(noticeId);
        MesWmSalesNoticeDO notice = cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(row,MesWmSalesNoticeDO.class);
        if (notice == null) {
            throw exception(WM_SALES_NOTICE_NOT_EXISTS);
        }
        if (ObjUtil.notEqual(PREPARE.getStatus(), notice.getStatus())) {
            throw exception(WM_SALES_NOTICE_STATUS_NOT_ALLOW_UPDATE);
        }
    }

    /**
     * 根据 batchCode 回填 batchId
     */
    private void fillBatchId(MesWmSalesNoticeLineDO line) {
        line.setBatchId(null);
        if (StrUtil.isEmpty(line.getBatchCode())) {
            return;
        }
        MesWmBatchDO batch = batchService.getBatchByCode(line.getBatchCode());
        if (batch != null && java.util.Objects.equals(batch.getItemId(),line.getItemId())) {
            line.setBatchId(batch.getId());
            line.setBatchCode(batch.getCode());
        } else throw exception(WM_PRODUCT_SALES_LINE_NOTICE_LINE_BATCH_MISMATCH);
    }

}
