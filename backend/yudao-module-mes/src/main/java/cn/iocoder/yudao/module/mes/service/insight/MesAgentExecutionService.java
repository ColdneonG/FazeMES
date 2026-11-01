package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.WorkorderCost;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.Profile;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import cn.iocoder.yudao.module.mes.service.dv.machinery.MesDvMachineryService;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;

/** Adapts reviewed commands to the same business services used by the ordinary pages. */
@Service
public class MesAgentExecutionService {
    @Resource private JdbcTemplate jdbc;
    @Resource private SecurityFrameworkService security;
    @Resource private MesProWorkOrderService workOrders;
    @Resource private MesCostService costs;
    @Resource private MesProductionIntegrityService integrity;
    @Resource private MesDvMachineryService machinery;

    private static final Map<Integer,String> ORDER_STATUS=Map.of(0,"草稿",1,"已确认",2,"已完成",3,"已取消");
    private static final Map<Integer,String> DEVICE_STATUS=Map.of(1,"停机",2,"生产中",3,"维护中");
    private static final Set<String> ORDER_ACTIONS=Set.of("CONFIRM_WORKORDER","CANCEL_WORKORDER","FINISH_WORKORDER","SETTLE_WORKORDER");

    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private static String text(Object value){return value==null?"":value.toString();}
    private static long number(Object value){if(!(value instanceof Number n))throw invalid("业务执行资料无效");return n.longValue();}
    private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    private void permission(String name){if(!security.hasPermission(name))throw invalid("当前账号缺少业务权限："+name);}
    private void authorize(String kind){
        if("UPDATE_DEVICE_STATUS".equals(kind)){permission("mes:dv-machinery:query");permission("mes:dv-machinery:update");}
        else if("UPDATE_PURCHASE_PRICE".equals(kind)){permission("mes:cost:query");permission("mes:cost:update");}
        else if(kind!=null&&ORDER_ACTIONS.contains(kind)){
            permission("mes:pro-work-order:query");
            if("SETTLE_WORKORDER".equals(kind)){permission("mes:cost:query");permission("mes:cost:settle");}
            else permission("mes:pro-work-order:update");
        }else throw invalid("不支持的业务执行操作");
    }

    /** Reference purchase price only; historical document prices and frozen plans remain independent. */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> preparePurchasePrice(String itemCode,String itemName,String purchasePrice,String note){
        authorize("UPDATE_PURCHASE_PRICE");
        BigDecimal amount=purchaseAmount(purchasePrice);
        String operationNote=note==null?"":note.strip();
        if(operationNote.length()>2000)throw invalid("操作说明不能超过2000字");
        String code=itemCode==null?"":itemCode.strip(),name=itemName==null?"":itemName.strip();
        if(code.length()>100||name.length()>255)throw invalid("物料编码或名称过长，请核对完整物料资料");
        if(code.isEmpty()&&name.isEmpty())throw invalid("请提供完整物料编码或精确物料名称");
        // Every profile writer locks its item before its price rows, including the first version.
        var matches=jdbc.queryForList("SELECT * FROM mes_md_item WHERE tenant_id=? AND deleted=0 AND "+(code.isEmpty()?"name":"code")+"=? ORDER BY id LIMIT 2 FOR UPDATE",tenant(),code.isEmpty()?name:code);
        if(matches.isEmpty())throw invalid("未找到当前租户的物料，请核对完整物料编码或精确名称");
        if(matches.size()!=1)throw invalid("匹配到多个同名物料或重复编码，请使用唯一的完整物料编码");
        var item=matches.get(0);long id=number(item.get("id"));
        if(!code.isEmpty()&&!name.isEmpty()&&!name.equals(text(item.get("name"))))throw invalid("物料编码与名称不一致，请核对后重新生成改价预览");
        Map<String,Object> price=currentPrice(id);
        int version=price==null?0:((Number)price.get("version")).intValue();
        return map("kind","UPDATE_PURCHASE_PRICE","itemId",id,"itemCode",item.get("code"),"itemName",item.get("name"),
            "title",item.get("name"),"oldPurchasePrice",price==null||price.get("purchase_price")==null?null:decimal(new BigDecimal(price.get("purchase_price").toString())),
            "purchasePrice",decimal(amount),"priceVersion",version,"newPriceVersion",version+1,"expectedPriceVersion",version,
            "currency","CNY","taxBasis","EXCLUSIVE","note",operationNote,
            "expectedState",fingerprint(item),"expectedPriceState",price==null?"NONE":fingerprint(price));
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> prepare(String kind,String orderCode,String deviceCode,Integer deviceStatus,String note){
        authorize(kind);
        String operationNote=note==null?"":note.strip();
        if(operationNote.length()>2000)throw invalid("操作说明不能超过2000字");
        boolean device="UPDATE_DEVICE_STATUS".equals(kind);
        Map<String,Object> row=byCode(device?"mes_dv_machinery":"mes_pro_work_order",device?deviceCode:orderCode,device?"设备":"工单");
        int before=((Number)row.get("status")).intValue();
        var payload=map("kind",kind,"title",text(row.get("name")),"note",operationNote,"expectedState",fingerprint(row));
        if(device){
            if(deviceStatus==null||!DEVICE_STATUS.containsKey(deviceStatus))throw invalid("请选择设备目标状态：停机、生产中或维护中");
            if(before==deviceStatus)throw invalid("设备已经处于目标状态，请刷新设备记录");
            payload.putAll(map("deviceId",number(row.get("id")),"deviceCode",row.get("code"),"fromStatus",before,"status",deviceStatus,
                "fromStatusName",DEVICE_STATUS.getOrDefault(before,"未知状态"),"statusName",DEVICE_STATUS.get(deviceStatus)));
        }else{
            validateOrderState(kind,before);
            int after=targetStatus(kind);
            payload.putAll(map("workOrderId",number(row.get("id")),"orderCode",row.get("code"),"fromStatus",before,"status",after,
                "fromStatusName",ORDER_STATUS.getOrDefault(before,"未知状态"),"statusName","SETTLE_WORKORDER".equals(kind)?"成本已结算":ORDER_STATUS.get(after)));
            if("SETTLE_WORKORDER".equals(kind))payload.putAll(settlement(number(row.get("id"))));
        }
        return payload;
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> execute(Map<String,Object> payload){
        String kind=text(payload.get("kind"));authorize(kind);
        if("UPDATE_PURCHASE_PRICE".equals(kind))return executePurchasePrice(payload);
        boolean device="UPDATE_DEVICE_STATUS".equals(kind);
        long id=number(payload.get(device?"deviceId":"workOrderId"));
        // Work-order confirmation has always locked the BOM graph before its order row.
        if("CONFIRM_WORKORDER".equals(kind))integrity.lockBomGraph();
        String table=device?"mes_dv_machinery":"mes_pro_work_order";
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",id,tenant());
        if(rows.size()!=1)throw invalid("业务记录不存在或无权访问");
        Map<String,Object> current=rows.get(0);
        if(!fingerprint(current).equals(text(payload.get("expectedState"))))throw invalid("业务记录已变化，请重新生成执行预览");
        String code=text(current.get("code")),message;
        int before=((Number)current.get("status")).intValue();
        if(device){
            int after=(int)number(payload.get("status"));
            machinery.updateMachineryStatus(id,before,after);
            assertStatus(table,id,after);
            message="设备 "+code+" 状态已更新为"+DEVICE_STATUS.get(after);
        }else{
            validateOrderState(kind,before);
            switch(kind){
                case "CONFIRM_WORKORDER" -> workOrders.confirmWorkOrder(id);
                case "CANCEL_WORKORDER" -> workOrders.cancelWorkOrder(id);
                case "FINISH_WORKORDER" -> workOrders.finishWorkOrder(id);
                case "SETTLE_WORKORDER" -> {
                    Map<String,Object> currentSettlement=settlement(id);
                    if(!currentSettlement.equals(settlementFields(payload)))throw invalid("结算金额或成本依据已变化，请重新生成执行预览");
                    costs.settle(id);
                    var statuses=jdbc.queryForList("SELECT status FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND deleted=0",tenant(),id);
                    if(statuses.size()!=1||!"SETTLED".equals(statuses.get(0).get("status")))throw invalid("成本结算结果未保存，请重试");
                }
                default -> throw invalid("不支持的工单操作");
            }
            assertStatus(table,id,targetStatus(kind));
            message="工单 "+code+switch(kind){case "CONFIRM_WORKORDER"->" 已确认";case "CANCEL_WORKORDER"->" 已取消";case "FINISH_WORKORDER"->" 已完成";default->" 实际成本已结算";};
        }
        return map("businessId",id,"businessCode",code,"message",message);
    }

    private Map<String,Object> executePurchasePrice(Map<String,Object> payload){
        long id=number(payload.get("itemId"));
        BigDecimal amount=purchaseAmount(text(payload.get("purchasePrice")));
        var rows=jdbc.queryForList("SELECT * FROM mes_md_item WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",id,tenant());
        if(rows.size()!=1)throw invalid("物料不存在或无权访问");
        var item=rows.get(0);
        if(!fingerprint(item).equals(text(payload.get("expectedState"))))throw invalid("物料资料已变化，请重新生成改价预览");
        Map<String,Object> price=currentPrice(id);
        int version=price==null?0:((Number)price.get("version")).intValue();
        String priceState=price==null?"NONE":fingerprint(price);
        if(version!=number(payload.get("expectedPriceVersion"))||!priceState.equals(text(payload.get("expectedPriceState"))))throw invalid("物料价格版本已变化，请重新生成改价预览");
        Profile prior=costs.profile(id),update=new Profile();update.setItemId(id);update.setPurchasePrice(amount);
        if(prior!=null){update.setSalePrice(prior.getSalePrice());update.setLaborCost(prior.getLaborCost());update.setOverheadCost(prior.getOverheadCost());update.setRemark(prior.getRemark());}
        costs.saveProfile(update);
        Profile saved=costs.profile(id);
        if(saved==null||saved.getVersion()!=version+1||saved.getPurchasePrice()==null||saved.getPurchasePrice().compareTo(amount)!=0)throw invalid("采购单价未保存，请重试");
        return map("businessId",id,"businessCode",item.get("code"),"priceVersion",saved.getVersion(),
            "message","物料 "+item.get("code")+"（"+item.get("name")+"）参考采购单价已修改为 "+decimal(amount)+" 元（人民币，不含税），价格版本 V"+saved.getVersion());
    }

    private Map<String,Object> currentPrice(long itemId){
        var prices=jdbc.queryForList("SELECT * FROM mes_cost_profile WHERE tenant_id=? AND item_id=? AND deleted=0 ORDER BY version DESC LIMIT 1 FOR UPDATE",tenant(),itemId);
        return prices.isEmpty()?null:prices.get(0);
    }
    private static BigDecimal purchaseAmount(String value){
        if(value==null||value.isBlank()||value.strip().length()>100)throw invalid("请填写有效采购单价，整数最多12位、小数最多6位");
        try{
            BigDecimal amount=new BigDecimal(value.strip());
            if(amount.signum()<0||amount.scale()>6||amount.precision()-amount.scale()>12)throw invalid("采购单价必须不小于0，整数最多12位、小数最多6位");
            return amount.setScale(6,RoundingMode.UNNECESSARY);
        }catch(NumberFormatException|ArithmeticException ex){throw invalid("请填写有效采购单价，整数最多12位、小数最多6位");}
    }

    private Map<String,Object> byCode(String table,String code,String label){
        if(code==null||code.isBlank()||code.strip().length()>100)throw invalid("请提供一个完整的"+label+"编码");
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE tenant_id=? AND code=? AND deleted=0",tenant(),code.strip());
        if(rows.size()!=1)throw invalid(label+"编码不存在或不唯一，请核对完整编码");
        return rows.get(0);
    }
    private static int targetStatus(String kind){return switch(kind){case "CONFIRM_WORKORDER"->1;case "CANCEL_WORKORDER"->3;case "FINISH_WORKORDER","SETTLE_WORKORDER"->2;default->throw invalid("不支持的工单操作");};}
    private static void validateOrderState(String kind,int status){
        int required="CONFIRM_WORKORDER".equals(kind)?0:"SETTLE_WORKORDER".equals(kind)?2:1;
        if(status!=required)throw invalid("CONFIRM_WORKORDER".equals(kind)?"仅草稿工单可以确认":"SETTLE_WORKORDER".equals(kind)?"须先完成工单后才能结算":"仅已确认工单可以完成或取消");
    }
    private static final List<String> SETTLEMENT_FIELDS=List.of("actualMaterialCost","actualLaborCost","actualOverheadCost","actualTotalCost","actualUnitCost","producedQuantity","costStatus");
    private Map<String,Object> settlement(long id){
        WorkorderCost value=costs.workorderCost(id);
        if(value==null)throw invalid("缺少工单成本依据，请查看成本明细");
        if("SETTLED".equals(value.getStatus()))throw invalid("该工单成本已结算，请查看成本明细");
        if(value.getPlanSnapshot()==null||value.getActualUnitCost()==null||value.getActualTotalCost()==null||!value.getIssues().isEmpty())throw invalid("成本证据不完整："+String.join("；",value.getIssues()));
        return map("actualMaterialCost",decimal(value.getActualMaterialCost()),"actualLaborCost",decimal(value.getActualLaborCost()),
            "actualOverheadCost",decimal(value.getActualOverheadCost()),"actualTotalCost",decimal(value.getActualTotalCost()),
            "actualUnitCost",decimal(value.getActualUnitCost()),"producedQuantity",decimal(value.getProducedQuantity()),"costStatus",value.getStatus());
    }
    private static Map<String,Object> settlementFields(Map<String,Object> payload){
        var values=new LinkedHashMap<String,Object>();for(String field:SETTLEMENT_FIELDS)values.put(field,payload.get(field));return values;
    }
    private void assertStatus(String table,long id,int expected){
        Integer current=jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=? AND tenant_id=? AND deleted=0",Integer.class,id,tenant());
        if(!Objects.equals(expected,current))throw invalid("业务状态未保存，请重试");
    }
    private static String fingerprint(Map<String,Object> row){
        var sorted=new TreeMap<String,Object>(row);var canonical=new StringBuilder();
        sorted.forEach((key,value)->{String string=value==null?"":value.toString();canonical.append(key.length()).append(':').append(key).append(value==null?'N':'V').append(string.length()).append(':').append(string);});
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
