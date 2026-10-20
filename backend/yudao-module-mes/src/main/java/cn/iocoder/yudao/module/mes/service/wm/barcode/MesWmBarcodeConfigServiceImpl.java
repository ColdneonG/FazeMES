package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.config.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.barcode.MesWmBarcodeConfigDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.barcode.MesWmBarcodeConfigMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.util.Objects;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeLabelPolicy.*;

@Service
@Validated
public class MesWmBarcodeConfigServiceImpl implements MesWmBarcodeConfigService {
    @Resource private MesWmBarcodeConfigMapper barcodeConfigMapper;
    @Resource private MesWmBarcodeMutex mutex;
    @Resource private MesWmBarcodeRecords records;
    private MesWmBarcodeConfigDO validated(MesWmBarcodeConfigSaveReqVO req,MesWmBarcodeConfigDO old){
        type(req.getBizType());format(req.getFormat());contentFormat(req.getContentFormat());
        Integer state=req.getStatus()==null?(old==null?0:old.getStatus()):req.getStatus();status(state);
        if(req.getAutoGenerateFlag()==null)throw invalid("自动生成标志不能为空");
        var conflict=records.config(req.getBizType(),true);
        if(conflict!=null&&!Objects.equals(conflict.getId(),req.getId()))throw exception(WM_BARCODE_CONFIG_BIZ_TYPE_DUPLICATE);
        if(old!=null&&!Objects.equals(old.getBizType(),req.getBizType())&&!records.barcodes(true,"config_id=?",old.getId()).isEmpty())throw invalid("已有条码关联的配置不能更改业务类型");
        return BeanUtils.toBean(req,MesWmBarcodeConfigDO.class).setStatus(state).setDefaultTemplate(template(req.getDefaultTemplate()));
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public Long createBarcodeConfig(MesWmBarcodeConfigSaveReqVO req){mutex.lock();if(req.getId()!=null)throw invalid("新增配置不能指定已有编号");var row=validated(req,null);barcodeConfigMapper.insert(row);return row.getId();}
    @Override @Transactional(rollbackFor=Exception.class)
    public void updateBarcodeConfig(MesWmBarcodeConfigSaveReqVO req){mutex.lock();var old=required(req.getId(),true);barcodeConfigMapper.updateById(validated(req,old));}
    @Override @Transactional(rollbackFor=Exception.class)
    public void deleteBarcodeConfig(Long id){mutex.lock();var old=required(id,true);if(!records.barcodes(true,"config_id=?",id).isEmpty())throw exception(WM_BARCODE_CONFIG_HAS_BARCODE);barcodeConfigMapper.deleteById(old.getId());}
    private MesWmBarcodeConfigDO required(Long id,boolean current){if(id==null)throw exception(WM_BARCODE_CONFIG_NOT_EXISTS);var rows=records.configs(current,"id=?",id);if(rows.isEmpty())throw exception(WM_BARCODE_CONFIG_NOT_EXISTS);return rows.get(0);}
    @Override public MesWmBarcodeConfigDO getBarcodeConfig(Long id){var rows=records.configs(false,"id=?",id);return rows.isEmpty()?null:rows.get(0);}
    @Override public PageResult<MesWmBarcodeConfigDO> getBarcodeConfigPage(MesWmBarcodeConfigPageReqVO req){return barcodeConfigMapper.selectPage(req);}
    @Override public MesWmBarcodeConfigDO getBarcodeConfigByBizType(Integer type){return records.config(type,false);}
    @Override public MesWmBarcodeConfigDO validateBarcodeConfigByBizType(Integer type){var config=records.config(type,false);MesWmBarcodeRecords.usableConfig(config);return config;}
}
