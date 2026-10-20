package cn.iocoder.yudao.module.mes.service.qc.rqc;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcSourceContextRespVO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.defectrecord.MesQcDefectRecordDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.MesQcTemplateItemDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcDefectLevelEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.defectrecord.MesQcDefectRecordService;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesService;
import cn.iocoder.yudao.module.mes.service.qc.template.MesQcTemplateItemService;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueLineService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesLineService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 退货检验单（RQC） Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesQcRqcServiceImpl implements MesQcRqcService {

    @Resource
    private MesQcRqcMapper rqcMapper;
    @Resource private JdbcTemplate jdbc;
    @Resource @Lazy private MesWmReturnSalesSourceService returnSalesSourceService;

    @Resource
    private MesQcTemplateItemService templateItemService;
    @Resource
    private MesQcRqcLineService rqcLineService;
    @Resource
    @Lazy
    private MesMdItemService itemService;
    @Resource
    @Lazy
    private MesQcDefectRecordService defectRecordService;
    @Resource
    @Lazy
    private MesWmReturnIssueLineService returnIssueLineService;
    @Resource
    @Lazy
    private MesWmReturnSalesLineService returnSalesLineService;
    @Resource
    @Lazy
    private MesWmReturnIssueService returnIssueService;
    @Resource
    @Lazy
    private MesWmReturnSalesService returnSalesService;
    @Resource
    @Lazy
    private MesQcIndicatorResultService indicatorResultService;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRqc(MesQcRqcSaveReqVO createReqVO) {
        MesQcRqcDO requested=BeanUtils.toBean(createReqVO,MesQcRqcDO.class);
        SourceLock source=lockSource(requested);
        String sourceDocCode=validateSource(requested,source);
        validateQuantities(requested,false);
        // 1.1 校验数据
        validateRqcSaveData(createReqVO);
        // 1.2 根据物料 + 检验类型自动匹配模板
        MesQcTemplateItemDO templateItem = templateItemService.getRequiredTemplateByItemIdAndType(
                createReqVO.getItemId(), MesQcTypeEnum.RQC.getType());
        Long templateId = templateItem.getTemplateId();
        // 1.3 获取来源单据编号

        // 2. 插入主表
        MesQcRqcDO rqc = BeanUtils.toBean(createReqVO, MesQcRqcDO.class)
                .setTemplateId(templateId)
                .setSourceDocCode(sourceDocCode)
                .setStatus(MesQcStatusEnum.DRAFT.getStatus());
        rqcMapper.insert(rqc);
        claimSource(rqc,source);

        // 3. 从模板指标自动生成检验行
        rqcLineService.createLinesFromTemplate(rqc.getId(), templateId);
        return rqc.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateRqc(MesQcRqcSaveReqVO updateReqVO) {
        // 1.1 校验存在 + 草稿状态
        LockedInspection locked=lockInspection(updateReqVO.getId());MesQcRqcDO current=locked.qc();requireDraft(current);
        MesQcRqcDO requested=BeanUtils.toBean(updateReqVO,MesQcRqcDO.class);
        if(!sameSource(current,requested)||!Objects.equals(current.getItemId(),requested.getItemId())
                ||!manual(current)&&(!Objects.equals(batch(current.getBatchCode()),batch(requested.getBatchCode()))
                ||!equal(current.getCheckQuantity(),requested.getCheckQuantity())))throw sourceInvalid("已创建的来源RQC不能更换来源、物料、批次或检测数量");
        String sourceCode=validateSource(requested,locked.source());validateQuantities(requested,false);
        // 1.2 校验数据
        validateRqcSaveData(updateReqVO);

        // 2. 更新
        MesQcRqcDO updateObj = BeanUtils.toBean(updateReqVO, MesQcRqcDO.class);
        updateObj.setSourceDocType(null).setSourceDocId(null).setSourceLineId(null); // 不允许修改来源单据
        updateObj.setTemplateId(null); // 不允许修改模板
        updateObj.setItemId(null); // 不允许修改物料
        updateObj.setSourceDocCode(sourceCode);
        rqcMapper.updateById(updateObj);
    }

    private void validateRqcSaveData(MesQcRqcSaveReqVO reqVO) {
        // 校验编号唯一
        validateRqcCodeUnique(reqVO.getId(), reqVO.getCode());
        // 校验物料、检测人员存在
        itemService.validateItemExists(reqVO.getItemId());
        adminUserApi.validateUser(reqVO.getInspectorUserId());
    }

    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private Long id(Object value){return value instanceof Number n?n.longValue():null;}
    private BigDecimal quantity(Object value){return value==null?null:new BigDecimal(value.toString());}
    private boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
    private boolean number(Object value,int expected){return value instanceof Number n&&n.intValue()==expected;}
    private String batch(String value){return value==null||value.isBlank()?null:value;}
    private boolean equal(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}
    private ServiceException sourceInvalid(String message){return new ServiceException(1_040_608_010,message);}
    private boolean manual(MesQcRqcDO qc){return (qc.getSourceDocType()==null||Objects.equals(qc.getSourceDocType(),0))&&qc.getSourceDocId()==null&&qc.getSourceLineId()==null;}
    private boolean sameSource(MesQcRqcDO a,MesQcRqcDO b){return manual(a)&&manual(b)||Objects.equals(a.getSourceDocType(),b.getSourceDocType())&&Objects.equals(a.getSourceDocId(),b.getSourceDocId())&&Objects.equals(a.getSourceLineId(),b.getSourceLineId());}
    private String lineTable(MesQcRqcDO qc){return Objects.equals(qc.getSourceDocType(),119)?"mes_wm_return_sales_line":"mes_wm_return_issue_line";}
    private String parentColumn(MesQcRqcDO qc){return Objects.equals(qc.getSourceDocType(),119)?"return_id":"issue_id";}
    private record SourceLock(Map<String,Object> parent,Long origin){}
    private record LockedInspection(MesQcRqcDO qc,SourceLock source){}
    private SourceLock lockSource(MesQcRqcDO qc){
        if(manual(qc))return null;
        if((!Objects.equals(qc.getSourceDocType(),119)&&!Objects.equals(qc.getSourceDocType(),116))||qc.getSourceDocId()==null||qc.getSourceLineId()==null)throw sourceInvalid("RQC来源必须是完整的销售退货或生产退料单据及行");
        Long origin=null;
        if(Objects.equals(qc.getSourceDocType(),119))origin=returnSalesSourceService.lockForRqc(qc.getSourceDocId(),qc.getSourceLineId());
        String table=Objects.equals(qc.getSourceDocType(),119)?"mes_wm_return_sales":"mes_wm_return_issue";
        String client=Objects.equals(qc.getSourceDocType(),119)?"client_id":"NULL AS client_id";
        var rows=jdbc.queryForList("SELECT id,code,status,"+client+" FROM "+table+" WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",qc.getSourceDocId(),tenant());
        if(rows.isEmpty())throw sourceInvalid("RQC来源退货单不存在或无权访问");
        return new SourceLock(rows.get(0),origin);
    }
    private Map<String,Object> sourceLine(MesQcRqcDO qc,SourceLock source){
        if(source==null)return null;
        String origin=Objects.equals(qc.getSourceDocType(),119)?"origin_transaction_id":"NULL AS origin_transaction_id";
        var rows=jdbc.queryForList("SELECT id,"+parentColumn(qc)+",item_id,batch_id,batch_code,quantity,rqc_check_flag,rqc_id,quality_status,"+origin+" FROM "+lineTable(qc)+" WHERE id=? AND "+parentColumn(qc)+"=? AND tenant_id=? AND deleted=0 FOR UPDATE",qc.getSourceLineId(),qc.getSourceDocId(),tenant());
        return rows.isEmpty()?null:rows.get(0);
    }
    private MesQcRqcDO currentInspection(Long id){MesQcRqcDO current=rqcMapper.selectOne(new LambdaQueryWrapperX<MesQcRqcDO>().eq(MesQcRqcDO::getId,id).last("FOR UPDATE"));if(current==null)throw exception(QC_RQC_NOT_EXISTS);return current;}
    private LockedInspection lockInspection(Long id){MesQcRqcDO located=validateRqcExists(id);SourceLock source=lockSource(located);MesQcRqcDO current=currentInspection(id);if(!sameSource(located,current))throw sourceInvalid("等待期间RQC来源发生变化，请刷新后重试");return new LockedInspection(current,source);}
    private void requireDraft(MesQcRqcDO qc){if(!Objects.equals(qc.getStatus(),MesQcStatusEnum.DRAFT.getStatus()))throw exception(QC_RQC_NOT_PREPARE);}
    private String validateSource(MesQcRqcDO qc,SourceLock source){return validateSource(qc,source,sourceLine(qc,source));}
    private String validateSource(MesQcRqcDO qc,SourceLock source,Map<String,Object> line){
        if(manual(qc))return null;
        if(source==null||line==null||!Objects.equals(id(source.parent().get("id")),qc.getSourceDocId())
                ||!Objects.equals(id(line.get(parentColumn(qc))),qc.getSourceDocId())||!Objects.equals(id(line.get("item_id")),qc.getItemId())
                ||!Objects.equals(batch((String)line.get("batch_code")),batch(qc.getBatchCode()))||!equal(quantity(line.get("quantity")),qc.getCheckQuantity()))throw sourceInvalid("RQC来源单据、行、物料、批次或检测数量与真实退货行不一致");
        if(!number(source.parent().get("status"),1)||!flag(line.get("rqc_check_flag"))||!number(line.get("quality_status"),0))throw sourceInvalid("来源退货行不处于待检状态或无需RQC");
        Long claim=id(line.get("rqc_id"));if(claim!=null&&claim==0L)claim=null;
        if(qc.getId()==null?claim!=null:!Objects.equals(claim,qc.getId()))throw sourceInvalid("来源退货行已被其他检验单认领，或当前RQC没有有效认领");
        if(qc.getCheckQuantity()==null||qc.getCheckQuantity().signum()<=0)throw sourceInvalid("来源检测数量必须大于零");
        if(Objects.equals(qc.getSourceDocType(),119))returnSalesSourceService.validateForRqc(qc.getSourceDocId(),qc.getSourceLineId(),source.origin());
        return Objects.toString(source.parent().get("code"),null);
    }
    private void claimSource(MesQcRqcDO qc,SourceLock source){if(source==null)return;if(qc.getId()==null)throw sourceInvalid("RQC创建失败");int updated=jdbc.update("UPDATE "+lineTable(qc)+" SET rqc_id=? WHERE id=? AND "+parentColumn(qc)+"=? AND tenant_id=? AND deleted=0 AND quality_status=0 AND (rqc_id IS NULL OR rqc_id=0)",qc.getId(),qc.getSourceLineId(),qc.getSourceDocId(),tenant());if(updated!=1)throw sourceInvalid("来源退货行已被认领，请刷新后重试");}
    private void validateQuantities(MesQcRqcDO qc,boolean finishing){
        // Preserve historical manual inspections without quantities; they have no stock callback.
        if(manual(qc)&&qc.getCheckQuantity()==null&&qc.getQualifiedQuantity()==null&&qc.getUnqualifiedQuantity()==null)return;
        BigDecimal checked=qc.getCheckQuantity(),good=qc.getQualifiedQuantity(),bad=qc.getUnqualifiedQuantity();
        if(checked==null||good==null||bad==null||checked.signum()<=0||good.signum()<0||bad.signum()<0||checked.scale()>6||good.scale()>6||bad.scale()>6||checked.precision()-checked.scale()>14||good.precision()-good.scale()>14||bad.precision()-bad.scale()>14||good.add(bad).compareTo(checked)!=0)throw exception(QC_RQC_QUANTITY_MISMATCH);
        if(qc.getCheckResult()!=null&&(!Objects.equals(qc.getCheckResult(),1)&&!Objects.equals(qc.getCheckResult(),2)
                ||Objects.equals(qc.getCheckResult(),1)&&bad.signum()>0||Objects.equals(qc.getCheckResult(),2)&&bad.signum()==0))throw sourceInvalid("RQC合格结论与不合格数量不一致");
        if(finishing&&qc.getCheckResult()==null)throw exception(QC_RQC_CHECK_RESULT_EMPTY);
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public MesQcRqcSourceContextRespVO getSourceContext(Integer sourceDocType,Long sourceDocId,Long sourceLineId){
        MesQcRqcDO qc=new MesQcRqcDO().setSourceDocType(sourceDocType).setSourceDocId(sourceDocId).setSourceLineId(sourceLineId);
        if(manual(qc))throw sourceInvalid("请选择待检的真实退货行");SourceLock source=lockSource(qc);var line=sourceLine(qc,source);if(line==null)throw sourceInvalid("来源退货行不存在或归属不匹配");
        qc.setItemId(id(line.get("item_id"))).setBatchCode((String)line.get("batch_code")).setCheckQuantity(quantity(line.get("quantity")));String code=validateSource(qc,source,line);
        return new MesQcRqcSourceContextRespVO().setSourceDocType(sourceDocType).setSourceDocId(sourceDocId).setSourceLineId(sourceLineId).setSourceDocCode(code).setClientId(id(source.parent().get("client_id"))).setItemId(qc.getItemId()).setBatchId(id(line.get("batch_id"))).setBatchCode(qc.getBatchCode()).setCheckQuantity(qc.getCheckQuantity()).setOriginTransactionId(id(line.get("origin_transaction_id")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishRqc(Long id) {
        // 1.1 校验存在 + 草稿状态
        LockedInspection locked=lockInspection(id);MesQcRqcDO rqc=locked.qc();requireDraft(rqc);
        String sourceCode=validateSource(rqc,locked.source());validateQuantities(rqc,true);
        // 1.2 校验检测结论必填
        if (rqc.getCheckResult() == null) {
            throw exception(QC_RQC_CHECK_RESULT_EMPTY);
        }
        if(!Objects.equals(rqc.getCheckResult(),1)&&!Objects.equals(rqc.getCheckResult(),2))throw sourceInvalid("RQC检测结论只能为合格或不合格");
        // 1.3 校验合格品 + 不合格品 = 检测数量
        if (rqc.getCheckQuantity() != null && rqc.getCheckQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal qualified = rqc.getQualifiedQuantity() != null ? rqc.getQualifiedQuantity() : BigDecimal.ZERO;
            BigDecimal unqualified = rqc.getUnqualifiedQuantity() != null ? rqc.getUnqualifiedQuantity() : BigDecimal.ZERO;
            if (qualified.add(unqualified).compareTo(rqc.getCheckQuantity()) != 0) {
                throw exception(QC_RQC_QUANTITY_MISMATCH);
            }
        }
        // 1.4 校验至少存在一条检测结果
        indicatorResultService.validateIndicatorResultExistsByQcIdAndType(id, MesQcTypeEnum.RQC.getType());

        // 2. 更新状态为已完成
        MesQcRqcDO updateObj = new MesQcRqcDO().setId(id).setStatus(MesQcStatusEnum.FINISHED.getStatus()).setSourceDocCode(sourceCode);
        rqcMapper.updateById(updateObj);

        // 3. 回写来源单据
        writeBackSourceDoc(rqc);
    }

    /**
     * 回写来源单据（RQC 完成后）
     *
     * <p>根据 sourceDocType 分发：
     * <ul>
     *     <li>WM_RETURN_ISSUE（生产退料单）→ 根据合格/不合格数量拆分行 + 更新 qualityStatus + 联动主单状态</li>
     *     <li>WM_RETURN_SALES（销售退货单）→ 同上</li>
     * </ul>
     *
     * @param rqc 退货检验单
     */
    private void writeBackSourceDoc(MesQcRqcDO rqc) {
        // Legacy schemas default an omitted source type to 0 for manual inspections.
        if (rqc.getSourceDocType() == null || (Objects.equals(rqc.getSourceDocType(), 0)
                && rqc.getSourceDocId() == null && rqc.getSourceLineId() == null)) {
            return;
        }
        if (rqc.getSourceLineId() == null) {
            throw new IllegalArgumentException(
                    "RQC 单[" + rqc.getId() + "] sourceDocType 非空但 sourceLineId 为空");
        }

        if (Objects.equals(rqc.getSourceDocType(), MesBizTypeConstants.WM_RETURN_ISSUE)) {
            // 回写生产退料单行：拆分行 + 更新质量状态 + 联动主单状态
            returnIssueLineService.updateReturnIssueLineWhenRqcFinish(
                    rqc.getSourceLineId(), rqc.getSourceDocId(), rqc.getCheckResult(),
                    rqc.getQualifiedQuantity(), rqc.getUnqualifiedQuantity());
        } else if (Objects.equals(rqc.getSourceDocType(), MesBizTypeConstants.WM_RETURN_SALES)) {
            // 回写销售退货单行：拆分行 + 更新质量状态 + 联动主单状态
            returnSalesLineService.updateReturnSalesLineWhenRqcFinish(
                    rqc.getSourceLineId(), rqc.getSourceDocId(), rqc.getCheckResult(),
                    rqc.getQualifiedQuantity(), rqc.getUnqualifiedQuantity());
        } else {
            throw new IllegalArgumentException(
                    "RQC 单[" + rqc.getId() + "] sourceDocType=" + rqc.getSourceDocType() + " 无法识别，无法回写来源单据");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRqc(Long id) {
        // An invalid old draft can be removed without ever calling a foreign source callback.
        MesQcRqcDO located=validateRqcExists(id);SourceLock source=null;
        try{source=lockSource(located);}catch(ServiceException|IllegalArgumentException invalidSource){/* remove only the invalid inspection */}
        MesQcRqcDO current=currentInspection(id);requireDraft(current);
        if(source!=null&&sameSource(located,current)){
            Map<String,Object> line=sourceLine(current,source);
            if(line!=null&&Objects.equals(id(line.get("rqc_id")),id))jdbc.update("UPDATE "+lineTable(current)+" SET rqc_id=NULL WHERE id=? AND "+parentColumn(current)+"=? AND tenant_id=? AND deleted=0 AND rqc_id=? AND quality_status=0",current.getSourceLineId(),current.getSourceDocId(),tenant(),id);
        }

        // 2.1 删除主表
        rqcMapper.deleteById(id);
        // 2.2 级联删除行
        rqcLineService.deleteByRqcId(id);
        // 2.3 级联删除缺陷记录
        defectRecordService.deleteListByQcTypeAndQcId(MesQcTypeEnum.RQC.getType(), id);
    }

    @Override
    public MesQcRqcDO validateRqcExists(Long id) {
        MesQcRqcDO rqc = rqcMapper.selectById(id);
        if (rqc == null) {
            throw exception(QC_RQC_NOT_EXISTS);
        }
        return rqc;
    }

    /**
     * 校验退货检验单存在且为草稿状态
     *
     * @param id 退货检验单 ID
     * @return 退货检验单
     */
    private MesQcRqcDO validateRqcStatusPrepare(Long id) {
        MesQcRqcDO rqc = validateRqcExists(id);
        if (ObjUtil.notEqual(rqc.getStatus(), MesQcStatusEnum.DRAFT.getStatus())) {
            throw exception(QC_RQC_NOT_PREPARE);
        }
        return rqc;
    }

    private void validateRqcCodeUnique(Long id, String code) {
        MesQcRqcDO rqc = rqcMapper.selectByCode(code);
        if (rqc == null) {
            return;
        }
        if (ObjUtil.notEqual(rqc.getId(), id)) {
            throw exception(QC_RQC_CODE_DUPLICATE);
        }
    }

    @Override
    public MesQcRqcDO getRqc(Long id) {
        return rqcMapper.selectById(id);
    }

    /**
     * 根据来源单据类型 + 来源单据 ID，推导来源单据编码
     */
    private String validateAndGetSourceDocCode(Integer sourceDocType, Long sourceDocId) {
        if (sourceDocType == null || sourceDocId == null) {
            return null;
        }
        if (Objects.equals(sourceDocType, MesBizTypeConstants.WM_RETURN_ISSUE)) {
            return returnIssueService.getReturnIssue(sourceDocId).getCode();
        } else if (Objects.equals(sourceDocType, MesBizTypeConstants.WM_RETURN_SALES)) {
            return returnSalesService.getReturnSales(sourceDocId).getCode();
        }
        return null;
    }

    @Override
    public PageResult<MesQcRqcDO> getRqcPage(MesQcRqcPageReqVO pageReqVO) {
        return rqcMapper.selectPage(pageReqVO);
    }

    @Override
    public void recalculateDefectStats(Long rqcId, List<MesQcDefectRecordDO> records) {
        MesQcRqcDO rqc = validateRqcExists(rqcId);
        // 1. 行级缺陷统计
        rqcLineService.recalculateLineDefectStats(rqcId, records);

        // 2.1 汇总主表的缺陷数量
        int totalCritical = 0;
        int totalMajor = 0;
        int totalMinor = 0;
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
        BigDecimal criticalRate = BigDecimal.ZERO;
        BigDecimal majorRate = BigDecimal.ZERO;
        BigDecimal minorRate = BigDecimal.ZERO;
        if (rqc.getCheckQuantity() != null && rqc.getCheckQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal checkQty = rqc.getCheckQuantity();
            criticalRate = BigDecimal.valueOf(totalCritical).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
            majorRate = BigDecimal.valueOf(totalMajor).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
            minorRate = BigDecimal.valueOf(totalMinor).multiply(BigDecimal.valueOf(100))
                    .divide(checkQty, 2, RoundingMode.HALF_UP);
        }
        // 2.3 更新主表
        MesQcRqcDO updateRqc = new MesQcRqcDO().setId(rqcId)
                .setCriticalQuantity(totalCritical).setMajorQuantity(totalMajor).setMinorQuantity(totalMinor)
                .setCriticalRate(criticalRate).setMajorRate(majorRate).setMinorRate(minorRate);
        rqcMapper.updateById(updateRqc);
    }

}
