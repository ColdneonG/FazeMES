package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.MesWmReturnSalesLinePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.MesWmReturnSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesLineMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnSalesStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.MD_ITEM_BATCH_REQUIRED;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_RETURN_SALES_LINE_NOT_EXISTS;

/**
 * MES 销售退货单行 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesWmReturnSalesLineServiceImpl implements MesWmReturnSalesLineService {

    @Resource
    private MesWmReturnSalesLineMapper returnSalesLineMapper;

    @Resource
    @Lazy
    private MesWmReturnSalesService returnSalesService;
    @Resource
    private MesMdItemService itemService;
    @Resource
    private MesWmBatchService batchService;
    @Resource
    private MesWmReturnSalesDeliveryService deliveryService;
    @Resource private MesWmReturnSalesSourceService sourceService;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Resource
    @Lazy
    private MesWmReturnSalesDetailService returnSalesDetailService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createReturnSalesLine(MesWmReturnSalesLineSaveReqVO createReqVO) {
        var parent=returnSalesService.validateReturnSalesExistsAndPrepare(createReqVO.getReturnId());
        sourceService.lockOriginIds(java.util.Collections.singleton(createReqVO.getOriginTransactionId()));
        canonicalSource(createReqVO,parent);
        validateLineSaveData(createReqVO);

        // 插入
        MesWmReturnSalesLineDO line = BeanUtils.toBean(createReqVO, MesWmReturnSalesLineDO.class);
        // 质量状态自动赋值
        line.setQualityStatus(calculateQualityStatus(line.getRqcCheckFlag()));
        returnSalesLineMapper.insert(line);
        sourceService.reserve(parent,line);
        return line.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateReturnSalesLine(MesWmReturnSalesLineSaveReqVO updateReqVO) {
        // A line cannot be moved out of a completed document by changing the request parent id.
        var persistedParent=returnSalesLineMapper.selectById(updateReqVO.getId());
        if(persistedParent==null) throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        updateReqVO.setReturnId(persistedParent.getReturnId());
        // 校验存在
        returnSalesService.validateReturnSalesExistsAndPrepare(persistedParent.getReturnId());
        sourceService.lockOriginIds(java.util.Arrays.asList(persistedParent.getOriginTransactionId(),updateReqVO.getOriginTransactionId()));
        MesWmReturnSalesLineDO oldLine = returnSalesLineMapper.selectByIdForUpdate(updateReqVO.getId());
        if (oldLine == null || !Objects.equals(oldLine.getReturnId(), persistedParent.getReturnId())
                || !Objects.equals(oldLine.getOriginTransactionId(),persistedParent.getOriginTransactionId())) {
            throw exception(WM_RETURN_SALES_LINE_NOT_EXISTS);
        }
        // 固定父单 ID，防止通过接口篡改
        updateReqVO.setReturnId(oldLine.getReturnId());
        var parent=returnSalesService.validateReturnSalesExistsAndPrepare(oldLine.getReturnId());
        sourceService.requireNoPosting(parent.getId());
        canonicalSource(updateReqVO,parent);
        validateLineSaveData(updateReqVO);

        // 更新
        MesWmReturnSalesLineDO updateObj = BeanUtils.toBean(updateReqVO, MesWmReturnSalesLineDO.class);
        // 质量状态自动赋值
        updateObj.setQualityStatus(calculateQualityStatus(updateObj.getRqcCheckFlag()));
        returnSalesLineMapper.updateById(updateObj);
        if(!Objects.equals(oldLine.getOriginTransactionId(),updateObj.getOriginTransactionId()))sourceService.releaseLine(oldLine.getId());
        sourceService.reserve(parent,updateObj);
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateLineSaveData(MesWmReturnSalesLineSaveReqVO reqVO) {
        // 校验退货单存在 + 草稿状态
        returnSalesService.validateReturnSalesExistsAndPrepare(reqVO.getReturnId());
        // 校验物料存在 + 批次管理
        validateItemBatchManagement(reqVO.getReturnId(), reqVO.getItemId(), reqVO.getBatchId());
        if (reqVO.getBatchId() == null) {
            if (cn.hutool.core.util.StrUtil.isNotBlank(reqVO.getBatchCode())) {
                throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
            }
            reqVO.setBatchCode(null);
        } else {
            var batch = batchService.validateBatchExists(reqVO.getBatchId(), reqVO.getItemId());
            if (cn.hutool.core.util.StrUtil.isNotBlank(reqVO.getBatchCode()) && !Objects.equals(reqVO.getBatchCode(), batch.getCode())) {
                throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
            }
            reqVO.setBatchCode(batch.getCode());
        }
        if (reqVO.getQuantity() == null || reqVO.getQuantity().signum() <= 0 || reqVO.getQuantity().scale() > 6
                || reqVO.getQuantity().precision() - reqVO.getQuantity().scale() > 14) {
            throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_RETURN_SALES_DETAIL_QUANTITY_MISMATCH);
        }
    }

    private void canonicalSource(MesWmReturnSalesLineSaveReqVO req,MesWmReturnSalesDO parent){
        var origin=sourceService.origin(parent.getClientId(),req.getOriginTransactionId());
        Long item=((Number)origin.get("item_id")).longValue(),batch=origin.get("batch_id")==null?null:((Number)origin.get("batch_id")).longValue();
        String code=origin.get("batch_code")==null?null:origin.get("batch_code").toString();
        if(req.getItemId()!=null&&!Objects.equals(req.getItemId(),item)||req.getBatchId()!=null&&!Objects.equals(req.getBatchId(),batch)
                ||cn.hutool.core.util.StrUtil.isNotBlank(req.getBatchCode())&&!Objects.equals(req.getBatchCode(),code))throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        req.setItemId(item);req.setBatchId(batch);req.setBatchCode(code);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnSalesLine(Long id) {
        // 校验存在
        MesWmReturnSalesLineDO line = validateReturnSalesLineExists(id);
        // 校验主单为草稿状态才允许删除行
        returnSalesService.validateReturnSalesExistsAndPrepare(line.getReturnId());
        sourceService.lockOriginIds(java.util.Collections.singleton(line.getOriginTransactionId()));
        var current = returnSalesLineMapper.selectByIdForUpdate(id);
        if (current == null || !Objects.equals(current.getReturnId(), line.getReturnId())||!Objects.equals(current.getOriginTransactionId(),line.getOriginTransactionId())) {
            throw exception(WM_RETURN_SALES_LINE_NOT_EXISTS);
        }
        sourceService.releaseLine(id);
        // 级联删除该行的明细
        returnSalesDetailService.deleteReturnSalesDetailByLineId(id);
        // 删除行
        returnSalesLineMapper.deleteById(id);
    }

    @Override
    public MesWmReturnSalesLineDO getReturnSalesLine(Long id) {
        return returnSalesLineMapper.selectById(id);
    }

    @Override
    public PageResult<MesWmReturnSalesLineDO> getReturnSalesLinePage(MesWmReturnSalesLinePageReqVO pageReqVO) {
        return returnSalesLineMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesWmReturnSalesLineDO> getReturnSalesLineListByReturnId(Long returnId) {
        return returnSalesLineMapper.selectListByReturnId(returnId);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnSalesLineByReturnId(Long returnId) {
        returnSalesService.validateReturnSalesExistsAndPrepare(returnId);
        for(var line:sourceService.lockDocumentSources(returnId))sourceService.releaseLine(line.getId());
        returnSalesLineMapper.deleteByReturnId(returnId);
    }

    @Override
    public MesWmReturnSalesLineDO validateReturnSalesLineExists(Long id) {
        MesWmReturnSalesLineDO line = returnSalesLineMapper.selectById(id);
        if (line == null) {
            throw exception(WM_RETURN_SALES_LINE_NOT_EXISTS);
        }
        return line;
    }

    @Override
    public void updateQualityStatusByReturnId(Long returnId, Integer qualityStatus) {
        List<MesWmReturnSalesLineDO> lines = returnSalesLineMapper.selectListByReturnId(returnId);
        for (MesWmReturnSalesLineDO line : lines) {
            Integer newStatus = calculateQualityStatus(line.getRqcCheckFlag());
            if (ObjUtil.notEqual(newStatus, line.getQualityStatus())) {
                returnSalesLineMapper.updateById(new MesWmReturnSalesLineDO()
                        .setId(line.getId()).setQualityStatus(newStatus));
            }
        }
    }

    private Integer calculateQualityStatus(Boolean rqcCheckFlag) {
        if (Boolean.TRUE.equals(rqcCheckFlag)) {
            return MesWmQualityStatusEnum.PENDING.getStatus(); // 待检
        }
        return MesWmQualityStatusEnum.PASS.getStatus(); // 不需检验，默认合格
    }

    /**
     * 校验物料批次管理
     *
     * @param returnId 退货单ID
     * @param itemId   物料ID
     * @param batchId  批次ID
     */
    private void validateItemBatchManagement(Long returnId, Long itemId, Long batchId) {
        MesMdItemDO item = itemService.validateItemExistsAndEnable(itemId);
        // 如果物料启用了批次管理，则必须选择批次
        if (Boolean.TRUE.equals(item.getBatchFlag()) && batchId == null) {
            throw exception(MD_ITEM_BATCH_REQUIRED);
        }
        MesWmReturnSalesDO returnSales = returnSalesService.validateReturnSalesExistsAndPrepare(returnId);
        deliveryService.validateDelivery(returnSales.getClientId(), itemId, batchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateReturnSalesLineWhenRqcFinish(Long sourceLineId, Long sourceDocId, Integer checkResult,
                                                    BigDecimal qualifiedQuantity, BigDecimal unqualifiedQuantity) {
        MesWmReturnSalesLineDO sourceLine = validateReturnSalesLineExists(sourceLineId);
        var parent = returnSalesService.validateReturnSalesExists(sourceLine.getReturnId());
        Long locatedOrigin=sourceLine.getOriginTransactionId();
        sourceService.lockOriginIds(java.util.Collections.singleton(locatedOrigin));
        Long locatedRqc=sourceLine.getRqcId();
        var actualQcs=jdbcTemplate.queryForList("SELECT * FROM mes_qc_rqc WHERE tenant_id=? AND source_doc_type=119 AND source_doc_id=? AND source_line_id=? AND deleted=0 ORDER BY id FOR UPDATE",cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.getRequiredTenantId(),parent.getId(),sourceLineId);
        java.util.Map<String,Object> actualQc=actualQcs.stream().filter(q->locatedRqc!=null&&Objects.equals(((Number)q.get("id")).longValue(),locatedRqc)).findFirst().orElse(null);
        sourceLine = returnSalesLineMapper.selectByIdForUpdate(sourceLineId);
        if (sourceLine == null || parent == null || !Objects.equals(parent.getStatus(), MesWmReturnSalesStatusEnum.CONFIRMED.getStatus())
                || (sourceDocId != null && !Objects.equals(parent.getId(), sourceDocId))
                || !Objects.equals(sourceLine.getReturnId(), parent.getId())
                || !Objects.equals(sourceLine.getOriginTransactionId(),locatedOrigin)
                || !Objects.equals(sourceLine.getRqcId(),locatedRqc)
                || !Objects.equals(sourceLine.getQualityStatus(), MesWmQualityStatusEnum.PENDING.getStatus())) {
            throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        }
        sourceService.validateLine(parent,sourceLine);
        sourceDocId = parent.getId(); // Always advance the actual locked source document, including legacy null callbacks.
        qualifiedQuantity = qualifiedQuantity == null ? BigDecimal.ZERO : qualifiedQuantity;
        unqualifiedQuantity = unqualifiedQuantity == null ? BigDecimal.ZERO : unqualifiedQuantity;
        cn.iocoder.yudao.module.mes.service.wm.quality.MesWmQualityPolicy.validateInspectionSplit(
                sourceLine.getQuantity(), qualifiedQuantity, unqualifiedQuantity, checkResult);
        validateActualRqc(sourceLine,parent,checkResult,qualifiedQuantity,unqualifiedQuantity,actualQc);
        boolean hasUnqualified = unqualifiedQuantity != null && unqualifiedQuantity.compareTo(BigDecimal.ZERO) > 0;
        boolean hasQualified = qualifiedQuantity != null && qualifiedQuantity.compareTo(BigDecimal.ZERO) > 0;

        // 1. 根据合格/不合格品数量，更新退货单行的质量状态
        if (!hasUnqualified) {
            // 1.A 情况一：全部合格
            returnSalesLineMapper.updateById(new MesWmReturnSalesLineDO()
                    .setId(sourceLineId).setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus()));
        } else if (!hasQualified) {
            // 1.B 情况二：全部不合格
            returnSalesLineMapper.updateById(new MesWmReturnSalesLineDO()
                    .setId(sourceLineId).setQualityStatus(MesWmQualityStatusEnum.FAIL.getStatus()));
        } else {
            // 1.C 情况三：部分合格部分不合格 → 拆分行
            // 1.C.1 新增一行不合格品
            MesWmReturnSalesLineDO unqualifiedLine = new MesWmReturnSalesLineDO()
                    .setReturnId(sourceLine.getReturnId())
                    .setOriginTransactionId(sourceLine.getOriginTransactionId())
                    .setItemId(sourceLine.getItemId())
                    .setQuantity(unqualifiedQuantity)
                    .setBatchId(sourceLine.getBatchId())
                    .setBatchCode(sourceLine.getBatchCode())
                    .setRqcId(sourceLine.getRqcId())
                    .setRqcCheckFlag(sourceLine.getRqcCheckFlag())
                    .setQualityStatus(MesWmQualityStatusEnum.FAIL.getStatus())
                    .setRemark(sourceLine.getRemark());
            returnSalesLineMapper.insert(unqualifiedLine);
            // 1.C.2 更新原行为合格品
            returnSalesLineMapper.updateById(new MesWmReturnSalesLineDO()
                    .setId(sourceLineId)
                    .setQuantity(qualifiedQuantity)
                    .setQualityStatus(MesWmQualityStatusEnum.PASS.getStatus()));
            sourceLine.setQuantity(qualifiedQuantity);
            sourceService.splitReservation(parent,sourceLine,unqualifiedLine);
        }

        // 2. 检查退货单下是否还有待检验的行，若全检完则将退货单状态设为"待上架"
        if (sourceDocId != null) {
            List<MesWmReturnSalesLineDO> allLines = returnSalesLineMapper.selectListByReturnIdForUpdate(sourceDocId);
            boolean allInspected = !CollUtil.contains(allLines,
                    line -> Objects.equals(line.getQualityStatus(), MesWmQualityStatusEnum.PENDING.getStatus()));
            if (allInspected) {
                returnSalesService.updateReturnSalesStatus(sourceDocId, MesWmReturnSalesStatusEnum.APPROVING.getStatus());
            }
        }
    }

    private void validateActualRqc(MesWmReturnSalesLineDO line,MesWmReturnSalesDO parent,Integer result,BigDecimal good,BigDecimal bad,java.util.Map<String,Object> qc){
        if(line.getRqcId()==null)throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        if(qc==null)throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        if(!Objects.equals(((Number)qc.get("status")).intValue(),4)||!Objects.equals(((Number)qc.get("source_doc_type")).intValue(),119)
                ||!Objects.equals(((Number)qc.get("source_doc_id")).longValue(),parent.getId())||!Objects.equals(((Number)qc.get("source_line_id")).longValue(),line.getId())
                ||!Objects.equals(((Number)qc.get("item_id")).longValue(),line.getItemId())||!Objects.equals(qc.get("batch_code"),line.getBatchCode())
                ||new BigDecimal(qc.get("check_quantity").toString()).compareTo(line.getQuantity())!=0
                ||!Objects.equals(((Number)qc.get("check_result")).intValue(),result)
                ||new BigDecimal(qc.get("qualified_quantity").toString()).compareTo(good==null?BigDecimal.ZERO:good)!=0
                ||new BigDecimal(qc.get("unqualified_quantity").toString()).compareTo(bad==null?BigDecimal.ZERO:bad)!=0)
            throw exception(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH);
    }

}
