package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.enums.dv.MesDvMachineryStatusEnum;
import cn.iocoder.yudao.module.mes.enums.pro.MesProWorkOrderStatusEnum;
import cn.iocoder.yudao.module.mes.enums.tm.MesTmToolStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmPackageStatusEnum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;

/** Resolves persisted business identities without injecting auto-generating source services. */
@Service
public class MesWmBarcodeBusinessResolver {
    private static final int IDENTITY_ERROR = 1_040_703_020;
    private final JdbcTemplate jdbc;

    public MesWmBarcodeBusinessResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record BusinessIdentity(Integer bizType,Long bizId,String bizCode,String bizName) { }

    private enum State {
        NONE(null), COMMON(CommonStatusEnum.ARRAYS), PACKAGE(MesWmPackageStatusEnum.ARRAYS),
        WORK_ORDER(MesProWorkOrderStatusEnum.ARRAYS), MACHINERY(MesDvMachineryStatusEnum.ARRAYS),
        TOOL(MesTmToolStatusEnum.ARRAYS);
        final Integer[] valid;
        State(Integer[] valid) { this.valid=valid; }
    }
    private record Source(String table,String code,String name,State state,boolean itemDisplay) { }
    // All SQL identifiers and expressions are server-owned constants; only id/tenant are bound values.
    private static final Map<Integer,Source> SOURCES=Map.ofEntries(
            Map.entry(102,new Source("mes_wm_warehouse","b.code","b.name",State.NONE,false)),
            Map.entry(103,new Source("mes_wm_warehouse_location","b.code","b.name",State.NONE,false)),
            Map.entry(104,new Source("mes_wm_warehouse_area","b.code","b.name",State.COMMON,false)),
            Map.entry(105,new Source("mes_wm_package","b.code","b.code",State.PACKAGE,false)),
            Map.entry(106,new Source("mes_wm_material_stock","b.id","i.name",State.NONE,true)),
            Map.entry(107,new Source("mes_wm_batch","b.code","i.name",State.NONE,true)),
            Map.entry(109,new Source("mes_wm_sn","b.code","b.code",State.NONE,false)),
            Map.entry(300,new Source("mes_pro_card","b.code","b.code",State.WORK_ORDER,false)),
            Map.entry(301,new Source("mes_pro_work_order","b.code","b.name",State.WORK_ORDER,false)),
            Map.entry(400,new Source("mes_dv_machinery","b.code","b.name",State.MACHINERY,false)),
            Map.entry(500,new Source("mes_tm_tool","b.code","b.name",State.TOOL,false)),
            Map.entry(600,new Source("mes_md_item","b.code","b.name",State.COMMON,false)),
            Map.entry(601,new Source("mes_md_vendor","b.code","b.name",State.COMMON,false)),
            Map.entry(602,new Source("mes_md_workstation","b.code","b.name",State.COMMON,false)),
            Map.entry(603,new Source("mes_md_workshop","b.code","b.name",State.COMMON,false)),
            Map.entry(604,new Source("system_users","b.username","b.nickname",State.COMMON,false)),
            Map.entry(605,new Source("mes_md_client","b.code","b.name",State.COMMON,false)));

    public BusinessIdentity resolve(Integer bizType,Long bizId,boolean forPrint) {
        if(bizType==null||bizId==null||bizId<=0) throw invalid("请选择有效的条码业务类型和业务记录编号");
        Source source=SOURCES.get(bizType);
        if(source==null) throw invalid(bizType==302||bizType==303
                ?"该业务类型尚未实现可靠的条码身份查询，暂不支持登记或打印"
                :"不支持该条码业务类型");
        Long tenant=TenantContextHolder.getTenantId();
        if(tenant==null) throw invalid("缺少当前租户信息，不能查询条码业务身份");
        String join=source.itemDisplay()?" LEFT JOIN mes_md_item i ON i.id=b.item_id AND i.tenant_id=b.tenant_id AND i.deleted=0":"";
        String state=source.state()==State.NONE?"":" ,b.status AS businessStatus";
        String display=source.itemDisplay()?",i.id AS displayItemId":"";
        var rows=jdbc.queryForList("SELECT b.id AS bizId,"+source.code()+" AS bizCode,"+source.name()+" AS bizName"+state+display+
                " FROM "+source.table()+" b"+join+" WHERE b.id=? AND b.tenant_id=? AND b.deleted=0 LIMIT 2",bizId,tenant);
        if(rows.size()!=1) throw invalid("条码关联的业务记录不存在、已删除或不属于当前租户");
        var row=rows.get(0);
        if(source.itemDisplay()&&row.get("displayItemId")==null)
            throw invalid("库存或批次关联的物料不存在、已删除或不属于当前租户，请先核对物料资料");
        validateState(source.state(),row.get("businessStatus"),forPrint);
        String code=row.get("bizCode")==null?null:row.get("bizCode").toString();
        if(code==null||code.isBlank()) throw invalid("业务记录缺少真实编码，请先维护原始业务资料");
        // Preserve the exact persisted code. In particular STOCK is the decimal stock ID, never itemCode.
        String name=row.get("bizName")==null?null:row.get("bizName").toString();
        Long actualId=((Number)row.get("bizId")).longValue();
        return new BusinessIdentity(bizType,actualId,code,name);
    }
    private static void validateState(State policy,Object raw,boolean forPrint) {
        if(policy==State.NONE) return;
        Integer status=raw instanceof Number n?n.intValue():null;
        if(status==null||!Arrays.asList(policy.valid).contains(status))
            throw invalid("业务记录状态无效，请先核对原始业务资料");
        if(forPrint&&policy==State.COMMON&&!CommonStatusEnum.isEnable(status))
            throw invalid("业务主数据已停用，请启用后再打印标签");
        // A lifecycle state such as FINISHED, MAINTENANCE or SCRAP is not common master disable.
    }
    private static ServiceException invalid(String message) { return new ServiceException(IDENTITY_ERROR,message); }
}
