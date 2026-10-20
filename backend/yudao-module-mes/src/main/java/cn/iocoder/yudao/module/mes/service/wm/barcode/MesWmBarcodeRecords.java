package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.barcode.*;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit tenant-scoped reads; current reads are only used after the registry mutex. */
@Component
public class MesWmBarcodeRecords {
    @Resource private JdbcTemplate jdbc;
    public List<MesWmBarcodeDO> barcodes(boolean current,String condition,Object...values){return query("mes_wm_barcode",MesWmBarcodeDO.class,current,condition,values);}
    public List<MesWmBarcodeConfigDO> configs(boolean current,String condition,Object...values){return query("mes_wm_barcode_config",MesWmBarcodeConfigDO.class,current,condition,values);}
    private <T> List<T> query(String table,Class<T> type,boolean current,String condition,Object...values){
        List<Object> args=new ArrayList<>();args.add(TenantContextHolder.getRequiredTenantId());Collections.addAll(args,values);
        return jdbc.query("SELECT * FROM "+table+" WHERE tenant_id=? AND deleted=0 AND ("+condition+") ORDER BY id LIMIT 2"+(current?" FOR UPDATE":""),BeanPropertyRowMapper.newInstance(type),args.toArray());
    }
    public MesWmBarcodeConfigDO config(Integer type,boolean current){var rows=configs(current,"biz_type=?",type);if(rows.size()>1)throw MesWmBarcodeLabelPolicy.invalid("同业务类型存在多个条码配置，请先修正历史重复配置");return rows.isEmpty()?null:rows.get(0);}
    public MesWmBarcodeDO barcode(boolean current,String condition,Object...values){var rows=barcodes(current,condition,values);if(rows.size()>1)throw MesWmBarcodeLabelPolicy.invalid("同业务对象存在多个条码登记，请先修正历史重复记录");return rows.isEmpty()?null:rows.get(0);}
    public static void usableConfig(MesWmBarcodeConfigDO config){if(config==null)throw MesWmBarcodeLabelPolicy.invalid("未配置此业务的条码规则，请先配置条码");MesWmBarcodeLabelPolicy.type(config.getBizType());MesWmBarcodeLabelPolicy.status(config.getStatus());if(config.getStatus()!=0)throw MesWmBarcodeLabelPolicy.invalid("条码配置已禁用");MesWmBarcodeLabelPolicy.format(config.getFormat());MesWmBarcodeLabelPolicy.template(config.getDefaultTemplate());MesWmBarcodeLabelPolicy.contentFormat(config.getContentFormat());if(config.getAutoGenerateFlag()==null)throw MesWmBarcodeLabelPolicy.invalid("条码配置自动生成标志无效，请修正配置");}
}
