package cn.iocoder.yudao.module.mes.service.qc.oqc;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo.MesQcOqcPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo.MesQcOqcSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.oqc.vo.MesQcOqcSourceContextRespVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.defectrecord.MesQcDefectRecordDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.MesQcTemplateItemDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcDefectLevelEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.service.md.client.MesMdClientService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.defectrecord.MesQcDefectRecordService;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.mes.service.qc.template.MesQcTemplateItemService;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesLineService;
import cn.iocoder.yudao.module.mes.service.wm.productsales.MesWmProductSalesService;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesLineMapper;
import cn.iocoder.yudao.module.mes.service.wm.salesnotice.MesWmSalesNoticeFulfillmentService;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 出货检验单（OQC） Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesQcOqcServiceImpl implements MesQcOqcService {

    @Resource
    private MesQcOqcMapper oqcMapper;

    @Resource
    private MesQcTemplateItemService templateItemService;
    @Resource
    private MesQcOqcLineService oqcLineService;
    @Resource
    @Lazy
    private MesQcDefectRecordService defectRecordService;
    @Resource
    @Lazy
    private MesMdClientService clientService;
    @Resource
    @Lazy
    private MesMdItemService itemService;
    @Resource
    @Lazy
    private MesWmProductSalesLineService productSalesLineService;
    @Resource
    @Lazy
    private MesWmProductSalesService productSalesService;
    @Resource private MesWmSalesNoticeFulfillmentService fulfillmentService;
    @Resource private MesWmProductSalesLineMapper productSalesLineMapper;
    @Resource
    @Lazy
    private MesQcIndicatorResultService indicatorResultService;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public MesQcOqcSourceContextRespVO getSourceContext(Long sourceDocId,Long sourceLineId) {
        MesQcOqcDO source=new MesQcOqcDO().setSourceDocType(MesBizTypeConstants.WM_PRODUCT_SALES).setSourceDocId(sourceDocId).setSourceLineId(sourceLineId);
        MesWmProductSalesDO parent=lockSource(source);
        MesWmProductSalesLineDO line=sourceLine(parent,sourceLineId);
        if(line==null)throw sourceInvalid("OQC来源销售行不存在或归属已变化");
        source.setItemId(line.getItemId()).setClientId(parent.getClientId()).setOutQuantity(line.getQuantity()).setBatchCode(line.getBatchCode());
        String code=validateSource(source,parent,line);
        var context=new MesQcOqcSourceContextRespVO();context.setSourceDocType(MesBizTypeConstants.WM_PRODUCT_SALES);context.setSourceDocId(parent.getId());context.setSourceLineId(line.getId());
        context.setSourceDocCode(code);context.setClientId(parent.getClientId());context.setItemId(line.getItemId());context.setBatchCode(line.getBatchCode());context.setOutQuantity(line.getQuantity());return context;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOqc(MesQcOqcSaveReqVO createReqVO) {
        MesQcOqcDO source=BeanUtils.toBean(createReqVO,MesQcOqcDO.class);
        MesWmProductSalesDO parent=lockSource(source);
        String sourceDocCode=validateSource(source,parent);
        // 1.1 校验数据
        validateOqcSaveData(createReqVO);
        // 1.3 根据产品 + 检验类型自动匹配模板
        MesQcTemplateItemDO templateItem = templateItemService.getRequiredTemplateByItemIdAndType(
                createReqVO.getItemId(), MesQcTypeEnum.OQC.getType());
        Long templateId = templateItem.getTemplateId();

        // 2. 插入主表
        MesQcOqcDO oqc = BeanUtils.toBean(createReqVO, MesQcOqcDO.class)
                .setTemplateId(templateId)
                .setSourceDocCode(sourceDocCode)
                .setStatus(MesQcStatusEnum.DRAFT.getStatus());
        oqcMapper.insert(oqc);

        // 3. 从模板指标自动生成检验行
        oqcLineService.createLinesFromTemplate(oqc.getId(), templateId);
        return oqc.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOqc(MesQcOqcSaveReqVO updateReqVO) {
        LockedInspection locked=lockInspection(updateReqVO.getId());
        MesQcOqcDO current=locked.oqc();
        requireDraft(current);
        MesQcOqcDO requested=BeanUtils.toBean(updateReqVO,MesQcOqcDO.class);
        if(!sameSource(current,requested) || !Objects.equals(current.getItemId(),requested.getItemId())
                || !manual(current) && !Objects.equals(current.getClientId(),requested.getClientId()))throw sourceInvalid("已创建OQC不能更换来源单据、来源行、物料或来源客户");
        String sourceCode=validateSource(requested,locked.parent());
        // 1.2 校验数据
        validateOqcSaveData(updateReqVO);

        // 2. 更新
        MesQcOqcDO updateObj = BeanUtils.toBean(updateReqVO, MesQcOqcDO.class);
        updateObj.setSourceDocType(null).setSourceDocId(null).setSourceLineId(null); // 不允许修改来源单据
        updateObj.setTemplateId(null); // 不允许修改模板
        updateObj.setItemId(null); // 不允许修改物料
        updateObj.setSourceDocCode(sourceCode);
        oqcMapper.updateById(updateObj);
    }

    private void validateOqcSaveData(MesQcOqcSaveReqVO reqVO) {
        // 校验编号唯一
        validateOqcCodeUnique(reqVO.getId(), reqVO.getCode());
        // 校验客户、物料、检测人员存在
        clientService.validateClientExists(reqVO.getClientId());
        itemService.validateItemExists(reqVO.getItemId());
        adminUserApi.validateUser(reqVO.getInspectorUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishOqc(Long id) {
        // Notice -> sales -> QC -> source line. Never lock QC and then enter a sales callback.
        LockedInspection locked=lockInspection(id);
        MesQcOqcDO oqc=locked.oqc();requireDraft(oqc);
        String sourceCode=validateSource(oqc,locked.parent());
        // 1.2 校验检测结论必填
        if (oqc.getCheckResult() == null) {
            throw exception(QC_OQC_CHECK_RESULT_EMPTY);
        }
        if(!Objects.equals(oqc.getCheckResult(),1) && !Objects.equals(oqc.getCheckResult(),2))throw sourceInvalid("检测结论只能为合格或不合格");
        // 1.3 校验至少存在一条检测结果
        indicatorResultService.validateIndicatorResultExistsByQcIdAndType(id, MesQcTypeEnum.OQC.getType());

        // 2. 更新状态为已完成
        MesQcOqcDO updateObj = new MesQcOqcDO()
                .setId(id).setStatus(MesQcStatusEnum.FINISHED.getStatus()).setSourceDocCode(sourceCode);
        oqcMapper.updateById(updateObj);

        // 3. 回写来源单据
        writeBackSourceDoc(oqc);
    }

    /**
     * 回写来源单据（OQC 完成后）
     *
     * <p>当来源为销售出库单时，调用
     * {@link MesWmProductSalesLineService#updateProductSalesLineWhenOqcFinish}
     * 回写行的 oqcId、qualityStatus，并由该方法内部联动检查出库单整体状态
     *
     * @param oqc 出货检验单
     */
    private void writeBackSourceDoc(MesQcOqcDO oqc) {
        // Legacy schemas default an omitted source type to 0 for manual inspections.
        if (oqc.getSourceDocType() == null || (Objects.equals(oqc.getSourceDocType(), 0)
                && oqc.getSourceDocId() == null && oqc.getSourceLineId() == null)) {
            return;
        }
        if (oqc.getSourceLineId() == null) {
            throw new IllegalArgumentException(
                    "OQC 单[" + oqc.getId() + "] sourceDocType 非空但 sourceLineId 为空");
        }

        if (Objects.equals(oqc.getSourceDocType(), MesBizTypeConstants.WM_PRODUCT_SALES)) {
            productSalesLineService.updateProductSalesLineWhenOqcFinish(
                    oqc.getSourceLineId(), oqc.getId(), oqc.getCheckResult());
        } else {
            throw new IllegalArgumentException(
                    "OQC 单[" + oqc.getId() + "] sourceDocType=" + oqc.getSourceDocType() + " 无法识别，无法回写来源单据");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOqc(Long id) {
        // Cleanup has no sales callback and takes no later source locks. Invalid/expired drafts remain removable.
        requireDraft(currentInspection(id));

        // 2.1 删除主表
        oqcMapper.deleteById(id);
        // 2.2 级联删除行
        oqcLineService.deleteByOqcId(id);
        // 2.3 级联删除缺陷记录
        defectRecordService.deleteListByQcTypeAndQcId(MesQcTypeEnum.OQC.getType(), id);
    }

    @Override
    public MesQcOqcDO validateOqcExists(Long id) {
        MesQcOqcDO oqc = oqcMapper.selectById(id);
        if (oqc == null) {
            throw exception(QC_OQC_NOT_EXISTS);
        }
        return oqc;
    }

    /**
     * 校验出货检验单存在且为草稿状态
     */
    private void requireDraft(MesQcOqcDO oqc) {
        if (ObjUtil.notEqual(oqc.getStatus(), MesQcStatusEnum.DRAFT.getStatus())) {
            throw exception(QC_OQC_NOT_PREPARE);
        }
    }

    private void validateOqcCodeUnique(Long id, String code) {
        MesQcOqcDO oqc = oqcMapper.selectByCode(code);
        if (oqc == null) {
            return;
        }
        if (ObjUtil.notEqual(oqc.getId(), id)) {
            throw exception(QC_OQC_CODE_DUPLICATE);
        }
    }

    /**
     * 校验来源单据参数，并验证数据存在
     *
     * @param sourceDocType 来源单据类型
     * @param sourceLineId  来源单据行 ID
     * @return 来源单据编号
     */
    private ServiceException sourceInvalid(String message) { return new ServiceException(1_040_607_008,message); }
    private boolean manual(MesQcOqcDO qc) {
        return (qc.getSourceDocType()==null || Objects.equals(qc.getSourceDocType(),0)) && qc.getSourceDocId()==null && qc.getSourceLineId()==null;
    }
    private boolean sameSource(MesQcOqcDO a,MesQcOqcDO b) {
        return (manual(a)&&manual(b)) || Objects.equals(a.getSourceDocType(),b.getSourceDocType())
                && Objects.equals(a.getSourceDocId(),b.getSourceDocId()) && Objects.equals(a.getSourceLineId(),b.getSourceLineId());
    }
    private MesWmProductSalesDO lockSource(MesQcOqcDO qc) {
        if(manual(qc))return null;
        if(!Objects.equals(qc.getSourceDocType(),MesBizTypeConstants.WM_PRODUCT_SALES))throw exception(QC_OQC_SOURCE_DOC_TYPE_UNKNOWN);
        if(qc.getSourceDocId()==null || qc.getSourceLineId()==null)throw sourceInvalid("销售来源OQC必须同时提供来源单据及来源行");
        MesWmProductSalesLineDO located=productSalesLineMapper.selectById(qc.getSourceLineId());
        if(located==null || !Objects.equals(located.getSalesId(),qc.getSourceDocId()))throw sourceInvalid("OQC来源行不属于指定销售单");
        return fulfillmentService.lockSales(located.getSalesId());
    }
    private record LockedInspection(MesQcOqcDO oqc,MesWmProductSalesDO parent) {}
    private MesQcOqcDO currentInspection(Long id) {
        MesQcOqcDO current=oqcMapper.selectOne(new LambdaQueryWrapperX<MesQcOqcDO>().eq(MesQcOqcDO::getId,id).last("FOR UPDATE"));
        if(current==null)throw exception(QC_OQC_NOT_EXISTS);
        return current;
    }
    private LockedInspection lockInspection(Long id) {
        MesQcOqcDO located=validateOqcExists(id);
        MesWmProductSalesDO parent=lockSource(located);
        MesQcOqcDO current=currentInspection(id);
        if(!sameSource(located,current))throw sourceInvalid("OQC来源在等待期间变化，请刷新后重试");
        return new LockedInspection(current,parent);
    }
    private String batch(String value) { return value==null || value.isBlank()?null:value; }
    private String validateSource(MesQcOqcDO qc,MesWmProductSalesDO parent) {
        if(manual(qc))return null;
        if(parent==null || !Objects.equals(parent.getId(),qc.getSourceDocId()))throw sourceInvalid("OQC来源销售单不存在或归属不匹配");
        return validateSource(qc,parent,sourceLine(parent,qc.getSourceLineId()));
    }
    private MesWmProductSalesLineDO sourceLine(MesWmProductSalesDO parent,Long sourceLineId) {
        // Lock only this parent's current set. A moved/corrupt row cannot make us lock another parent's line.
        return productSalesLineMapper.selectListBySalesIdForUpdate(parent.getId()).stream()
                .filter(row->Objects.equals(row.getId(),sourceLineId)).findFirst().orElse(null);
    }
    private String validateSource(MesQcOqcDO qc,MesWmProductSalesDO parent,MesWmProductSalesLineDO line) {
        if(line==null || !Objects.equals(line.getSalesId(),parent.getId()) || !Objects.equals(line.getItemId(),qc.getItemId())
                || !Objects.equals(parent.getClientId(),qc.getClientId()))throw sourceInvalid("OQC来源单据、行、物料或客户与真实销售记录不一致");
        if(!Objects.equals(parent.getStatus(),1) || !Boolean.TRUE.equals(line.getOqcCheckFlag())
                || !Objects.equals(line.getQualityStatus(),0) || line.getOqcId()!=null)throw sourceInvalid("销售行不处于待检状态，不能建立或完成来源OQC");
        if(qc.getOutQuantity()==null || qc.getOutQuantity().signum()<=0 || qc.getOutQuantity().scale()>6 || qc.getOutQuantity().precision()-qc.getOutQuantity().scale()>14
                || line.getQuantity()==null || qc.getOutQuantity().compareTo(line.getQuantity())!=0
                || !Objects.equals(batch(qc.getBatchCode()),batch(line.getBatchCode())))throw sourceInvalid("OQC出货数量或批次与来源销售行不一致");
        fulfillmentService.requireNoPosting(parent.getId());
        return parent.getCode();
    }

    @Override
    public MesQcOqcDO getOqc(Long id) {
        return oqcMapper.selectById(id);
    }

    @Override
    public PageResult<MesQcOqcDO> getOqcPage(MesQcOqcPageReqVO pageReqVO) {
        return oqcMapper.selectPage(pageReqVO);
    }

    @Override
    public void recalculateDefectStats(Long oqcId, List<MesQcDefectRecordDO> records) {
        // 1. 行级缺陷统计
        oqcLineService.recalculateLineDefectStats(oqcId, records);

        // 2.1 汇总主表的缺陷数量
        int totalCritical = 0, totalMajor = 0, totalMinor = 0;
        for (MesQcDefectRecordDO record : records) {
            int quantity = ObjUtil.defaultIfNull(record.getQuantity(), 1);
            if (Objects.equals(record.getLevel(), MesQcDefectLevelEnum.CRITICAL.getType())) {
                totalCritical += quantity;
            } else if (Objects.equals(record.getLevel(), MesQcDefectLevelEnum.MAJOR.getType())) {
                totalMajor += quantity;
            } else if (Objects.equals(record.getLevel(), MesQcDefectLevelEnum.MINOR.getType())) {
                totalMinor += quantity;
            } else {
                throw exception(QC_DEFECT_RECORD_LEVEL_UNKNOWN);
            }
        }
        // 2.2 计算缺陷率
        MesQcOqcDO oqc = validateOqcExists(oqcId);
        BigDecimal criticalRate = BigDecimal.ZERO;
        BigDecimal majorRate = BigDecimal.ZERO;
        BigDecimal minorRate = BigDecimal.ZERO;
        if (oqc.getCheckQuantity() != null && oqc.getCheckQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal checkQty = oqc.getCheckQuantity();
            criticalRate = BigDecimal.valueOf(totalCritical).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
            majorRate = BigDecimal.valueOf(totalMajor).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
            minorRate = BigDecimal.valueOf(totalMinor).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
        }
        // 2.3 更新主表
        MesQcOqcDO updateOqc = new MesQcOqcDO().setId(oqcId)
                .setCriticalQuantity(totalCritical).setMajorQuantity(totalMajor).setMinorQuantity(totalMinor)
                .setCriticalRate(criticalRate).setMajorRate(majorRate).setMinorRate(minorRate);
        oqcMapper.updateById(updateOqc);
    }

}
