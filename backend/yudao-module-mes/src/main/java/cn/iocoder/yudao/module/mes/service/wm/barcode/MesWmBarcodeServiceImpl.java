package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.barcode.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.barcode.MesWmBarcodeMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeLabelPolicy.*;

@Service
@Validated
public class MesWmBarcodeServiceImpl implements MesWmBarcodeService {
    @Resource private MesWmBarcodeMapper barcodeMapper;
    @Resource private MesWmBarcodeBusinessResolver businessResolver;
    @Resource private MesWmBarcodeMutex mutex;
    @Resource private MesWmBarcodeRecords records;
    @Resource private JdbcTemplate jdbc;

    @Override @Transactional(rollbackFor=Exception.class)
    public Long createBarcode(MesWmBarcodeSaveReqVO req){
        mutex.lock();if(req.getId()!=null)throw invalid("新增条码不能指定已有编号");
        var identity=businessResolver.resolve(req.getBizType(),req.getBizId(),false);
        var config=records.config(req.getBizType(),true);MesWmBarcodeRecords.usableConfig(config);
        String value=validatedContent(req,config,identity.bizCode());unique(null,req.getBizType(),req.getBizId(),value);
        Integer state=req.getStatus()==null?0:req.getStatus();status(state);
        var row=BeanUtils.toBean(req,MesWmBarcodeDO.class).setId(null).setBizCode(identity.bizCode()).setBizName(identity.bizName()).setConfigId(config.getId()).setFormat(config.getFormat()).setContent(value).setStatus(state);
        barcodeMapper.insert(row);return row.getId();
    }

    @Override @Transactional(rollbackFor=Exception.class)
    public void updateBarcode(MesWmBarcodeSaveReqVO req){
        mutex.lock();var old=required(req.getId(),true);
        var identity=businessResolver.resolve(req.getBizType(),req.getBizId(),false);
        var config=records.config(req.getBizType(),true);MesWmBarcodeRecords.usableConfig(config);
        String value=validatedContent(req,config,identity.bizCode());unique(old.getId(),req.getBizType(),req.getBizId(),value);
        Integer state=req.getStatus()==null?old.getStatus():req.getStatus();status(state);
        barcodeMapper.updateById(BeanUtils.toBean(req,MesWmBarcodeDO.class).setBizCode(identity.bizCode()).setBizName(identity.bizName()).setConfigId(config.getId()).setFormat(config.getFormat()).setContent(value).setStatus(state));
    }
    private String validatedContent(MesWmBarcodeSaveReqVO req,MesWmBarcodeConfigDO config,String code){
        String value=req.getContent();if(value==null||value.isEmpty())value=generate(config.getContentFormat(),code);
        content(config.getFormat(),value);return value;
    }
    private void unique(Long own,Integer type,Long bizId,String value){
        for(var row:records.barcodes(true,"biz_type=? AND biz_id=?",type,bizId))if(!Objects.equals(own,row.getId()))throw exception(WM_BARCODE_ALREADY_EXISTS);
        for(var row:records.barcodes(true,"content=?",value))if(!Objects.equals(own,row.getId()))throw exception(WM_BARCODE_CONTENT_DUPLICATE);
    }
    private MesWmBarcodeDO required(Long id,boolean current){if(id==null||id<=0)throw exception(WM_BARCODE_NOT_EXISTS);var row=records.barcode(current,"id=?",id);if(row==null)throw exception(WM_BARCODE_NOT_EXISTS);return row;}
    @Override @Transactional(rollbackFor=Exception.class)
    public void deleteBarcode(Long id){mutex.lock();var row=required(id,true);barcodeMapper.deleteById(row.getId());}
    @Override @Transactional(rollbackFor=Exception.class)
    public void deleteBarcodeByBizTypeAndBizIds(Integer type,Collection<Long> ids){
        if(ids==null||ids.isEmpty())return;
        mutex.lock();type(type);
        List<Object> args=new ArrayList<>();args.add(TenantContextHolder.getRequiredTenantId());args.add(type);args.addAll(ids);
        jdbc.update("UPDATE mes_wm_barcode SET deleted=1 WHERE tenant_id=? AND biz_type=? AND deleted=0 AND biz_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
    }
    @Override public MesWmBarcodeDO getBarcode(Long id){return records.barcode(false,"id=?",id);}
    @Override public PageResult<MesWmBarcodeDO> getBarcodePage(MesWmBarcodePageReqVO req){return barcodeMapper.selectPage(req);}
    @Override public MesWmBarcodeDO getBarcodeByBizTypeAndBizId(Integer type,Long id){return records.barcode(false,"biz_type=? AND biz_id=?",type,id);}
    @Override public long getBarcodeCountByConfigId(Long id){return barcodeMapper.selectCountByConfigId(id);}

    @Override @Transactional(rollbackFor=Exception.class)
    public void autoGenerateBarcode(Integer type,Long id,String ignoredCode,String ignoredName){
        mutex.lock();type(type);var config=records.config(type,true);
        if(config==null)return;
        status(config.getStatus());
        if(config.getStatus()==1||Boolean.FALSE.equals(config.getAutoGenerateFlag()))return;
        MesWmBarcodeRecords.usableConfig(config);
        var existing=records.barcode(true,"biz_type=? AND biz_id=?",type,id);if(existing!=null)return;
        var identity=businessResolver.resolve(type,id,false);
        createBarcode(new MesWmBarcodeSaveReqVO().setBizType(type).setBizId(id).setBizCode(identity.bizCode()).setBizName(identity.bizName()).setStatus(0));
    }
    @Override public String generateBarcodeContent(Integer type,String code){
        type(type);if(code==null||code.isBlank())throw invalid("业务编码不能为空");
        var config=records.config(type,false);MesWmBarcodeRecords.usableConfig(config);
        String value=generate(config.getContentFormat(),code);content(config.getFormat(),value);return value;
    }

    @Override @Transactional(readOnly=true)
    public MesWmBarcodeLabelContextRespVO getLabelContext(Long barcodeId,Integer bizType,Long bizId){
        boolean byBarcode=barcodeId!=null;
        if(byBarcode?(barcodeId<=0||bizType!=null||bizId!=null):(bizType==null||bizId==null||bizId<=0))throw invalid("请只指定条码编号，或完整的业务类型与业务编号");
        MesWmBarcodeDO registered=byBarcode?required(barcodeId,false):null;
        if(byBarcode){bizType=registered.getBizType();bizId=registered.getBizId();}
        var identity=businessResolver.resolve(bizType,bizId,true);
        var own=records.barcode(false,"biz_type=? AND biz_id=?",bizType,bizId);
        if(registered==null)registered=own;else if(own==null||!Objects.equals(registered.getId(),own.getId()))throw invalid("条码登记身份不一致，请修正记录");
        var config=records.config(bizType,false);MesWmBarcodeRecords.usableConfig(config);
        var out=new MesWmBarcodeLabelContextRespVO().setBarcodeId(registered==null?null:registered.getId()).setRegistered(registered!=null).setConfigId(config.getId()).setBizType(bizType).setBizId(bizId).setBizCode(identity.bizCode()).setBizName(identity.bizName()).setDefaultTemplate(template(config.getDefaultTemplate()));
        if(registered==null){
            String candidate=generate(config.getContentFormat(),identity.bizCode());content(config.getFormat(),candidate);
            if(!records.barcodes(false,"content=?",candidate).isEmpty())throw invalid("候选条码内容已用于其他业务，请在条码管理中明确指定唯一内容");
            return out.setFormat(config.getFormat()).setCandidateContent(candidate);
        }
        status(registered.getStatus());if(registered.getStatus()!=0)throw invalid("条码已禁用，请修正后再打印");
        if(!Objects.equals(registered.getConfigId(),config.getId()))throw invalid("登记条码的配置已失效，请在条码管理中修正");
        content(registered.getFormat(),registered.getContent());
        for(var other:records.barcodes(false,"content=?",registered.getContent()))if(!Objects.equals(other.getId(),registered.getId()))throw invalid("相同条码内容对应多个业务对象，无法可靠扫描，请修正历史记录");
        return out.setFormat(registered.getFormat()).setContent(registered.getContent());
    }
}
