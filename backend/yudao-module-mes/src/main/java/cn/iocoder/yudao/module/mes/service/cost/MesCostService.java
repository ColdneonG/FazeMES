package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/**
 * Manufacturing costing is deliberately separate from the stock quantity ledger.
 * Reference prices are never used to fill gaps in actual inventory valuation.
 * SQL always includes tenant and deletion predicates; JdbcTemplate bypasses MyBatis interceptors.
 */
@Service
public class MesCostService {
    @Resource private JdbcTemplate jdbc;
    @Resource private cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService integrityService;
    private static final int MAX_NODES = 2000;
    private static final int MAX_DEPTH = 32;
    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private static ServiceException invalid(String message) { return new ServiceException(1_023_900_001, message); }
    private static BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    private static Long id(Object value) { return value == null ? null : ((Number)value).longValue(); }
    private static BigDecimal money(BigDecimal value) { return value == null ? null : value.setScale(6, RoundingMode.HALF_UP); }
    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal ratio(BigDecimal profit, BigDecimal revenue) {
        return profit == null || revenue == null || revenue.signum() == 0 ? null : profit.divide(revenue, 8, RoundingMode.HALF_UP);
    }
    private Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Map<String,Object> workorder(Long workorderId, boolean lock) {
        Map<String,Object> row = one("SELECT * FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""), workorderId, tenant());
        if (row == null) throw invalid("工单不存在或无权访问");
        return row;
    }
    private Map<String,Object> item(Long itemId, boolean lock) {
        Map<String,Object> row=one("SELECT * FROM mes_md_item WHERE id=? AND tenant_id=? AND deleted=0"+(lock?" FOR UPDATE":""),itemId,tenant());
        if(row==null) throw invalid("物料不存在或无权访问");
        return row;
    }
    public Profile profile(Long itemId) {
        return profile(itemId,false);
    }
    private Profile profile(Long itemId,boolean current) {
        item(itemId,current);
        List<Profile> rows=jdbc.query("SELECT * FROM mes_cost_profile WHERE tenant_id=? AND item_id=? AND deleted=0 ORDER BY version DESC LIMIT 1"+readLock(current),BeanPropertyRowMapper.newInstance(Profile.class),tenant(),itemId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Profile> profileHistory(Long itemId) {
        item(itemId,false);
        return jdbc.query("SELECT * FROM mes_cost_profile WHERE tenant_id=? AND item_id=? AND deleted=0 ORDER BY version DESC",BeanPropertyRowMapper.newInstance(Profile.class),tenant(),itemId);
    }
    @Transactional(rollbackFor=Exception.class)
    public void saveProfile(Profile req) {
        item(req.getItemId(),true); // Serializes versions, including simultaneous first versions.
        Profile last=profile(req.getItemId(),true);
        jdbc.update("INSERT INTO mes_cost_profile(tenant_id,item_id,version,purchase_price,sale_price,labor_cost,overhead_cost,remark,creator) VALUES(?,?,?,?,?,?,?,?,?)",tenant(),req.getItemId(),last==null?1:last.getVersion()+1,req.getPurchasePrice(),req.getSalePrice(),req.getLaborCost(),req.getOverheadCost(),req.getRemark(),String.valueOf(getLoginUserId()));
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProductCost product(Long itemId) {
        Map<Long,Leaf> leaves=new LinkedHashMap<>();
        ProductCost result=expand(itemId,BigDecimal.ONE,new LinkedHashSet<>(),new int[]{0},leaves,BigDecimal.ONE,null,false);
        result.setLeaves(new ArrayList<>(leaves.values()));
        return result;
    }
    private static String readLock(boolean current){return current?" FOR UPDATE":"";}
    private ProductCost expand(Long itemId,BigDecimal quantity,Set<Long> path,int[] count,Map<Long,Leaf> leaves,BigDecimal multiplier,List<Map<String,Object>> orderBom,boolean current) {
        if(path.size()>=MAX_DEPTH || ++count[0]>MAX_NODES) throw invalid("BOM 超过核算安全上限（32 层 / 2000 节点）");
        if(!path.add(itemId)) throw invalid("BOM 存在循环引用，无法核算");
        Map<String,Object> item=item(itemId,current);
        ProductCost node=new ProductCost();node.setItemId(itemId);node.setItemCode((String)item.get("code"));node.setItemName((String)item.get("name"));node.setQuantity(quantity);
        if(item.get("unit_measure_id")!=null){Map<String,Object> unit=one("SELECT name FROM mes_md_unit_measure WHERE id=? AND tenant_id=? AND deleted=0"+readLock(current),item.get("unit_measure_id"),tenant());if(unit!=null)node.setUnitMeasureName((String)unit.get("name"));}
        Profile p=profile(itemId,current);
        if(p!=null){node.setPriceVersionId(p.getId());node.setPurchasePrice(p.getPurchasePrice());node.setSalePrice(p.getSalePrice());}
        List<Map<String,Object>> rows=orderBom!=null?orderBom:jdbc.queryForList("SELECT bom_item_id,quantity FROM mes_md_product_bom WHERE item_id=? AND tenant_id=? AND deleted=0 AND status=0 ORDER BY id"+readLock(current),itemId,tenant());
        Map<String,Object> type=one("SELECT item_or_product FROM mes_md_item_type WHERE id=? AND tenant_id=? AND deleted=0"+readLock(current),item.get("item_type_id"),tenant());
        boolean manufactured=type!=null && "PRODUCT".equals(type.get("item_or_product"));
        if(type==null) node.getIssues().add("物料分类缺失");
        if(rows.isEmpty()) {
            if(manufactured) node.getIssues().add("自制产品缺少启用的 BOM："+node.getItemCode());
            else if(p==null || p.getPurchasePrice()==null) node.getIssues().add("缺少参考采购价："+node.getItemCode());
            if(node.getIssues().isEmpty()) {node.setMaterialCost(p.getPurchasePrice());node.setLaborCost(BigDecimal.ZERO);node.setOverheadCost(BigDecimal.ZERO);node.setTotalCost(p.getPurchasePrice());}
            Leaf leaf=leaves.computeIfAbsent(itemId,k->{Leaf l=new Leaf();l.setItemId(k);l.setItemCode(node.getItemCode());l.setItemName(node.getItemName());l.setUnitMeasureName(node.getUnitMeasureName());l.setQuantity(BigDecimal.ZERO);l.setPurchasePrice(manufactured?null:node.getPurchasePrice());return l;});
            leaf.setQuantity(leaf.getQuantity().add(multiplier));leaf.setAmount(leaf.getPurchasePrice()==null?null:money(leaf.getQuantity().multiply(leaf.getPurchasePrice())));
        } else {
            BigDecimal materials=BigDecimal.ZERO, childLabor=BigDecimal.ZERO, childOverhead=BigDecimal.ZERO;
            Set<Long> children=new HashSet<>();
            for(Map<String,Object> row:rows) {
                Long childId=id(row.get("bom_item_id"));BigDecimal q=decimal(row.get("quantity"));
                if(q==null || q.signum()<=0 || !children.add(childId)) throw invalid("BOM 子项重复或用量无效");
                ProductCost child=expand(childId,q,path,count,leaves,multiplier.multiply(q),null,current);node.getChildren().add(child);
                if(child.getTotalCost()==null) node.getIssues().addAll(child.getIssues());
                else {materials=materials.add(child.getMaterialCost().multiply(q));childLabor=childLabor.add(child.getLaborCost().multiply(q));childOverhead=childOverhead.add(child.getOverheadCost().multiply(q));}
            }
            if(p==null || p.getLaborCost()==null)node.getIssues().add("缺少本层标准人工成本："+node.getItemCode());
            if(p==null || p.getOverheadCost()==null)node.getIssues().add("缺少本层标准制造费用："+node.getItemCode());
            node.setMaterialCost(node.getChildren().stream().anyMatch(c->c.getTotalCost()==null)?null:money(materials));
            boolean childrenComplete=node.getChildren().stream().noneMatch(c->c.getTotalCost()==null);
            node.setLaborCost(p==null || p.getLaborCost()==null || !childrenComplete?null:money(childLabor.add(p.getLaborCost())));
            node.setOverheadCost(p==null || p.getOverheadCost()==null || !childrenComplete?null:money(childOverhead.add(p.getOverheadCost())));
            if(node.getIssues().isEmpty()) node.setTotalCost(money(node.getMaterialCost().add(node.getLaborCost()).add(node.getOverheadCost())));
        }
        if(!node.getIssues().isEmpty())node.setStatus("INCOMPLETE");
        path.remove(itemId);return node;
    }
    /** Called by work-order confirmation, in the same transaction. */
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void capturePlan(Long workorderId) {
        // Same order as work-order confirmation: graph mutex -> work order -> cost rows.
        integrityService.lockBomGraph();
        Map<String,Object> order=workorder(workorderId,true);
        if(one("SELECT id FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND deleted=0 FOR UPDATE",tenant(),workorderId)!=null)return;
        if(((Number)order.get("status")).intValue()!=1)throw invalid("仅已确认且尚未执行的工单可冻结计划成本");
        Map<String,Object> events=one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND work_order_id=? AND deleted=0 LIMIT 1 FOR UPDATE",tenant(),workorderId);
        Map<String,Object> feedbacks=one("SELECT id FROM mes_pro_feedback WHERE tenant_id=? AND work_order_id=? AND deleted=0 LIMIT 1 FOR UPDATE",tenant(),workorderId);
        if(events!=null || feedbacks!=null)throw invalid("工单已有执行记录，不能补造计划成本快照");
        BigDecimal plannedQuantity=decimal(order.get("quantity"));
        if(plannedQuantity==null || plannedQuantity.signum()<=0)throw invalid("工单计划数量无效");
        List<Map<String,Object>> orderBom=jdbc.queryForList("SELECT item_id AS bom_item_id,quantity FROM mes_pro_work_order_bom WHERE tenant_id=? AND work_order_id=? AND deleted=0 ORDER BY id FOR UPDATE",tenant(),workorderId);
        if(orderBom.isEmpty())throw invalid("确认成本计划前须生成工单 BOM");
        for(Map<String,Object> line:orderBom)line.put("quantity",decimal(line.get("quantity")).divide(plannedQuantity,18,RoundingMode.HALF_UP));
        Set<Long> priceItems=new TreeSet<>();priceItems.add(id(order.get("product_id")));
        int[] collected=new int[]{0};
        for(Map<String,Object> line:orderBom)collectPriceItems(id(line.get("bom_item_id")),priceItems,new HashSet<>(),0,collected);
        // Every price writer locks its item first. A sorted set avoids cross-product lock inversion.
        priceItems.forEach(key->item(key,true));
        Map<Long,Leaf> leaves=new LinkedHashMap<>();
        ProductCost plan=expand(id(order.get("product_id")),BigDecimal.ONE,new LinkedHashSet<>(),new int[]{0},leaves,BigDecimal.ONE,orderBom,true);
        plan.setLeaves(new ArrayList<>(leaves.values()));
        jdbc.update("INSERT INTO mes_cost_workorder(tenant_id,workorder_id,plan_snapshot,plan_quantity) VALUES(?,?,?,?)",tenant(),workorderId,JsonUtils.toJsonString(plan),order.get("quantity"));
    }
    private void collectPriceItems(Long itemId,Set<Long> items,Set<Long> path,int depth,int[] count) {
        if(depth>=MAX_DEPTH || ++count[0]>MAX_NODES || !path.add(itemId))throw invalid("BOM 循环或超过核算安全上限");
        items.add(itemId);
        for(Map<String,Object> edge:jdbc.queryForList("SELECT bom_item_id FROM mes_md_product_bom WHERE item_id=? AND tenant_id=? AND deleted=0 AND status=0 ORDER BY id FOR UPDATE",itemId,tenant()))collectPriceItems(id(edge.get("bom_item_id")),items,path,depth+1,count);
        path.remove(itemId);
    }
    public void assertWorkOrderMutable(Long workorderId) {
        workorder(workorderId,true);
        if(one("SELECT id FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND status='SETTLED' AND deleted=0 FOR UPDATE",tenant(),workorderId)!=null)throw invalid("工单成本已结算，禁止更改执行和成本记录");
    }
    @Transactional(rollbackFor=Exception.class)
    public void addEffort(Effort req) {
        assertWorkOrderMutable(req.getWorkorderId());
        if(one("SELECT id FROM mes_pro_task WHERE work_order_id=? AND process_id=? AND tenant_id=? AND deleted=0 AND status<>5 LIMIT 1 FOR UPDATE",req.getWorkorderId(),req.getProcessId(),tenant())==null)throw invalid("工序不属于该工单的有效任务");
        Map<String,Object> prior=one("SELECT * FROM mes_cost_effort WHERE tenant_id=? AND request_id=? AND deleted=0 FOR UPDATE",tenant(),req.getRequestId());
        if(prior!=null) {
            if(Boolean.TRUE.equals(prior.get("voided")) || prior.get("voided") instanceof Number n && n.intValue()!=0)throw invalid("该工时登记已撤销；如需重新登记请使用新的提交标识");
            if(!Objects.equals(id(prior.get("workorder_id")),req.getWorkorderId()) || !Objects.equals(id(prior.get("process_id")),req.getProcessId()) || decimal(prior.get("hours")).compareTo(req.getHours())!=0 || decimal(prior.get("hourly_rate")).compareTo(req.getHourlyRate())!=0 || decimal(prior.get("overhead_amount")).compareTo(req.getOverheadAmount())!=0 || !Objects.equals(prior.get("remark"),req.getRemark()))throw invalid("工时请求标识已用于不同内容");
            return;
        }
        jdbc.update("INSERT INTO mes_cost_effort(tenant_id,workorder_id,process_id,hours,hourly_rate,overhead_amount,remark,request_id,creator) VALUES(?,?,?,?,?,?,?,?,?)",tenant(),req.getWorkorderId(),req.getProcessId(),req.getHours(),req.getHourlyRate(),req.getOverheadAmount(),req.getRemark(),req.getRequestId(),String.valueOf(getLoginUserId()));
    }
    @Transactional(rollbackFor=Exception.class)
    public void voidEffort(VoidEffort req) {
        Map<String,Object> row=one("SELECT workorder_id FROM mes_cost_effort WHERE id=? AND tenant_id=? AND deleted=0",req.getId(),tenant());
        if(row==null)throw invalid("工时登记不存在或无权访问");
        assertWorkOrderMutable(id(row.get("workorder_id")));
        row=one("SELECT * FROM mes_cost_effort WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",req.getId(),tenant());
        boolean voided=Boolean.TRUE.equals(row.get("voided")) || row.get("voided") instanceof Number n && n.intValue()!=0;
        if(voided){if(Objects.equals(req.getReason(),row.get("void_reason")))return;throw invalid("该登记已撤销，原撤销依据不能覆盖");}
        jdbc.update("UPDATE mes_cost_effort SET voided=1,void_reason=?,void_time=NOW(),void_by=? WHERE id=? AND tenant_id=? AND deleted=0 AND voided=0",req.getReason(),String.valueOf(getLoginUserId()),req.getId(),tenant());
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public WorkorderCost workorderCost(Long workorderId) {
        return workorderCost(workorderId,false);
    }
    private WorkorderCost workorderCost(Long workorderId,boolean current) {
        workorder(workorderId,current);
        WorkorderCost result=new WorkorderCost();result.setWorkorderId(workorderId);result.setStatus("PROVISIONAL");
        Map<String,Object> snapshot=one("SELECT * FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND deleted=0"+readLock(current),tenant(),workorderId);
        if(snapshot!=null){result.setPlanSnapshot(JsonUtils.parseObject((String)snapshot.get("plan_snapshot"),ProductCost.class));result.setPlanQuantity(decimal(snapshot.get("plan_quantity")));
            ProductCost plan=result.getPlanSnapshot();BigDecimal qty=result.getPlanQuantity();
            result.setPlanMaterialCost(plan.getMaterialCost()==null?null:money(plan.getMaterialCost().multiply(qty)));
            result.setPlanLaborCost(plan.getLaborCost()==null?null:money(plan.getLaborCost().multiply(qty)));
            result.setPlanOverheadCost(plan.getOverheadCost()==null?null:money(plan.getOverheadCost().multiply(qty)));
            result.setPlanTotalCost(plan.getTotalCost()==null?null:money(plan.getTotalCost().multiply(qty)));
        }
        else result.getIssues().add("缺少执行前冻结的计划快照");
        result.setEfforts(jdbc.query("SELECT * FROM mes_cost_effort WHERE tenant_id=? AND workorder_id=? AND deleted=0 ORDER BY id"+readLock(current),BeanPropertyRowMapper.newInstance(Effort.class),tenant(),workorderId));
        BigDecimal labor=BigDecimal.ZERO,overhead=BigDecimal.ZERO;
        int activeEfforts=0;
        for(Effort e:result.getEfforts()){if(Boolean.TRUE.equals(e.getVoided()))continue;activeEfforts++;labor=labor.add(e.getHours().multiply(e.getHourlyRate()));overhead=overhead.add(e.getOverheadAmount());}
        result.setActualLaborCost(activeEfforts==0?null:money(labor));result.setActualOverheadCost(activeEfforts==0?null:money(overhead));
        if(activeEfforts==0)result.getIssues().add("未录入实际工时及制造费用（零费用也须明确记录）");
        Set<Long> recordedProcesses=new HashSet<>();for(Effort e:result.getEfforts())if(!Boolean.TRUE.equals(e.getVoided()))recordedProcesses.add(e.getProcessId());
        List<Map<String,Object>> processes=jdbc.queryForList("SELECT process_id FROM mes_pro_task WHERE tenant_id=? AND work_order_id=? AND deleted=0 AND status<>5 ORDER BY id"+readLock(current),tenant(),workorderId);
        if(processes.stream().anyMatch(p->!recordedProcesses.contains(id(p.get("process_id")))))result.getIssues().add("仍有工序未明确录入实际工时及制造费用");
        List<Map<String,Object>> consumed=jdbc.queryForList("SELECT id,quantity FROM mes_wm_transaction WHERE tenant_id=? AND work_order_id=? AND biz_type=? AND quantity<0 AND deleted=0 ORDER BY id"+readLock(current),tenant(),workorderId,MesBizTypeConstants.WM_ITEM_CONSUME);
        BigDecimal material=BigDecimal.ZERO;boolean complete=!consumed.isEmpty();
        for(Map<String,Object> row:consumed) {
            List<Map<String,Object>> allocations=jdbc.queryForList("SELECT * FROM mes_cost_allocation WHERE transaction_id=? AND tenant_id=? AND deleted=0 ORDER BY id"+readLock(current),row.get("id"),tenant());
            BigDecimal allocated=BigDecimal.ZERO;boolean rowComplete=!allocations.isEmpty();
            for(Map<String,Object> allocation:allocations){allocated=allocated.add(decimal(allocation.get("quantity")));
                if(!"FINAL".equals(allocation.get("status")) || allocation.get("unit_cost")==null || allocation.get("material_amount")==null || allocation.get("labor_amount")==null || allocation.get("overhead_amount")==null)rowComplete=false;
                else {material=material.add(decimal(allocation.get("material_amount")));labor=labor.add(decimal(allocation.get("labor_amount")));overhead=overhead.add(decimal(allocation.get("overhead_amount")));}
            }
            if(!rowComplete || allocated.compareTo(decimal(row.get("quantity")).abs())!=0)complete=false;
        }
        if(!complete)result.getIssues().add("实际耗料缺少完整已确认的采购/半成品成本证据");
        result.setActualMaterialCost(complete?money(material):null);
        result.setActualLaborCost(complete && activeEfforts>0?money(labor):null);
        result.setActualOverheadCost(complete && activeEfforts>0?money(overhead):null);
        BigDecimal produced=BigDecimal.ZERO;boolean badOutput=false,missingQuality=false;
        List<Map<String,Object>> outputs=jdbc.queryForList("SELECT t.quantity,l.quality_status FROM mes_wm_transaction t LEFT JOIN mes_wm_product_produce_line l ON l.id=t.biz_line_id AND l.tenant_id=t.tenant_id AND l.produce_id=t.biz_id AND l.item_id=t.item_id AND l.deleted=0 WHERE t.tenant_id=? AND t.work_order_id=? AND t.biz_type=? AND t.quantity>0 AND t.deleted=0 ORDER BY t.id"+readLock(current),tenant(),workorderId,MesBizTypeConstants.WM_PRODUCT_PRODUCE);
        for(Map<String,Object> output:outputs){Integer quality=output.get("quality_status")==null?null:((Number)output.get("quality_status")).intValue();
            if(Objects.equals(quality,cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum.PASS.getStatus()))produced=produced.add(decimal(output.get("quantity")));
            else if(Objects.equals(quality,cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum.FAIL.getStatus()))badOutput=true;
            else missingQuality=true;
        }
        if(badOutput)result.getIssues().add("存在不良产出，须完成报废/返工处置及分摊规则后才能结算");
        if(missingQuality)result.getIssues().add("产出缺少可核验的合格质量记录，不能结算");
        result.setProducedQuantity(produced);
        if(produced.signum()==0)result.getIssues().add("尚无合格产出");
        if(complete && activeEfforts>0 && !badOutput && !missingQuality) {
            result.setActualTotalCost(money(material.add(labor).add(overhead)));
            if(produced.signum()>0)result.setActualUnitCost(result.getActualTotalCost().divide(produced,8,RoundingMode.HALF_UP));
        }
        if(snapshot!=null && "SETTLED".equals(snapshot.get("status"))) {
            result.setStatus("SETTLED");result.setActualMaterialCost(decimal(snapshot.get("actual_material_cost")));result.setActualLaborCost(decimal(snapshot.get("actual_labor_cost")));result.setActualOverheadCost(decimal(snapshot.get("actual_overhead_cost")));result.setActualTotalCost(decimal(snapshot.get("actual_total_cost")));result.setProducedQuantity(decimal(snapshot.get("produced_quantity")));result.setActualUnitCost(decimal(snapshot.get("actual_unit_cost")));
        } else if(!result.getIssues().isEmpty())result.setStatus("INCOMPLETE");
        if(result.getPlanTotalCost()!=null && result.getActualTotalCost()!=null)result.setTotalVariance(money(result.getActualTotalCost().subtract(result.getPlanTotalCost())));
        if(result.getPlanSnapshot()!=null && result.getPlanSnapshot().getTotalCost()!=null && result.getActualUnitCost()!=null)result.setUnitVariance(money(result.getActualUnitCost().subtract(result.getPlanSnapshot().getTotalCost())));
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public void settle(Long workorderId) {
        Map<String,Object> order=workorder(workorderId,true);
        Map<String,Object> existing=one("SELECT status FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND deleted=0 FOR UPDATE",tenant(),workorderId);
        if(existing!=null && "SETTLED".equals(existing.get("status")))return;
        if(((Number)order.get("status")).intValue()!=2)throw invalid("须先完成工单后才能结算");
        if(one("SELECT id FROM mes_pro_feedback WHERE tenant_id=? AND work_order_id=? AND deleted=0 AND status<>4 LIMIT 1 FOR UPDATE",tenant(),workorderId)!=null)throw invalid("仍有未完成的报工/检验记录");
        if(one("SELECT id FROM mes_wm_material_stock WHERE tenant_id=? AND work_order_id=? AND deleted=0 AND quantity>0 LIMIT 1 FOR UPDATE",tenant(),workorderId)!=null)throw invalid("工单仍有未清理的线边库存，不能结算");
        WorkorderCost result=workorderCost(workorderId,true);
        if(result.getPlanSnapshot()==null || result.getActualUnitCost()==null || !result.getIssues().isEmpty())throw invalid("成本证据不完整："+String.join("；",result.getIssues()));
        jdbc.update("UPDATE mes_cost_workorder SET status='SETTLED',actual_material_cost=?,actual_labor_cost=?,actual_overhead_cost=?,actual_total_cost=?,produced_quantity=?,actual_unit_cost=?,settled_time=NOW(),settled_by=? WHERE tenant_id=? AND workorder_id=? AND status<>'SETTLED' AND deleted=0",result.getActualMaterialCost(),result.getActualLaborCost(),result.getActualOverheadCost(),result.getActualTotalCost(),result.getProducedQuantity(),result.getActualUnitCost(),String.valueOf(getLoginUserId()),tenant(),workorderId);
        // This also finalizes transferred output layers. Already-executed outbound snapshots stay immutable.
        jdbc.update("UPDATE mes_cost_stock_layer SET unit_cost=?,status='FINAL' WHERE tenant_id=? AND origin_workorder_id=? AND deleted=0",result.getActualUnitCost(),tenant(),workorderId);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LinePrice linePrice(String kind,Long lineId) {
        Map<String,Object> line=line(kind,lineId,false);
        List<LinePrice> rows=jdbc.query("SELECT * FROM mes_cost_line_price WHERE tenant_id=? AND kind=? AND line_id=? AND deleted=0",BeanPropertyRowMapper.newInstance(LinePrice.class),tenant(),kind,lineId);
        LinePrice result=rows.isEmpty()?new LinePrice():rows.get(0);result.setLineId(lineId);
        boolean receipt="RECEIPT".equals(kind);
        result.setQuantity(decimal(line.get(receipt?"received_quantity":"quantity")));
        if(result.getUnitPrice()!=null)result.setAmount(money(result.getUnitPrice().multiply(result.getQuantity())));
        String header=receipt?"mes_wm_item_receipt":"mes_wm_product_sales";
        Map<String,Object> document=one("SELECT status FROM "+header+" WHERE id=? AND tenant_id=? AND deleted=0",line.get(receipt?"receipt_id":"sales_id"),tenant());
        result.setLocked(document==null || ((Number)document.get("status")).intValue()!=0);return result;
    }
    private Map<String,Object> line(String kind,Long lineId,boolean lock) {
        String table="RECEIPT".equals(kind)?"mes_wm_item_receipt_line":"mes_wm_product_sales_line";
        Map<String,Object> row=one("SELECT * FROM "+table+" WHERE id=? AND tenant_id=? AND deleted=0"+(lock?" FOR UPDATE":""),lineId,tenant());
        if(row==null)throw invalid("单据行不存在或无权访问");return row;
    }
    @Transactional(rollbackFor=Exception.class)
    public void saveLinePrice(String kind,LinePrice req) {
        Map<String,Object> row=line(kind,req.getLineId(),false);
        // The business document row is the same lock that execute uses. Always lock it as well.
        boolean receipt="RECEIPT".equals(kind);String header=receipt?"mes_wm_item_receipt":"mes_wm_product_sales";
        Map<String,Object> document=one("SELECT status FROM "+header+" WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",row.get(receipt?"receipt_id":"sales_id"),tenant());
        Map<String,Object> lockedLine=line(kind,req.getLineId(),true);
        if(!Objects.equals(lockedLine.get(receipt?"receipt_id":"sales_id"),row.get(receipt?"receipt_id":"sales_id")))throw invalid("单据行归属已改变，请刷新后重试");
        int biz=receipt?MesBizTypeConstants.WM_ITEM_RECEIPT_IN:MesBizTypeConstants.WM_PRODUCT_SALES;
        Map<String,Object> executed=one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=? AND biz_line_id=? AND deleted=0 LIMIT 1 FOR UPDATE",tenant(),biz,req.getLineId());
        if(document==null || ((Number)document.get("status")).intValue()!=0 || executed!=null)throw invalid("仅草稿单据可维护单价；已执行价格不可覆盖");
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price,remark,creator) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE unit_price=VALUES(unit_price),remark=VALUES(remark),creator=VALUES(creator)",tenant(),kind,req.getLineId(),req.getUnitPrice(),req.getRemark(),String.valueOf(getLoginUserId()));
    }
    /** Called once, after stock transaction INSERT, in its surrounding stock transaction. */
    @Transactional(rollbackFor=Exception.class)
    public void recordTransaction(MesWmTransactionDO transaction,MesWmMaterialStockDO stockBefore) {
        long t=tenant();
        if(transaction.getQuantity().signum()<0) {
            if(Objects.equals(transaction.getBizType(),MesBizTypeConstants.WM_ITEM_CONSUME))assertWorkOrderMutable(transaction.getWorkOrderId());
            List<Map<String,Object>> layers=jdbc.queryForList("SELECT * FROM mes_cost_stock_layer WHERE tenant_id=? AND material_stock_id=? AND remaining_quantity>0 AND deleted=0 ORDER BY id FOR UPDATE",t,stockBefore.getId());
            BigDecimal accounted=layers.stream().map(l->decimal(l.get("remaining_quantity"))).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(accounted.compareTo(stockBefore.getQuantity())>0)throw invalid("库存数量与成本层不一致，事务已回滚");
            if(accounted.compareTo(stockBefore.getQuantity())<0){insertLayer(stockBefore.getId(),null,null,stockBefore.getQuantity().subtract(accounted),null,"INCOMPLETE");layers=jdbc.queryForList("SELECT * FROM mes_cost_stock_layer WHERE tenant_id=? AND material_stock_id=? AND remaining_quantity>0 AND deleted=0 ORDER BY id FOR UPDATE",t,stockBefore.getId());}
            BigDecimal remaining=transaction.getQuantity().abs();
            for(Map<String,Object> layer:layers) {
                if(remaining.signum()==0)break;
                BigDecimal take=remaining.min(decimal(layer.get("remaining_quantity")));
                if(Objects.equals(transaction.getBizType(),MesBizTypeConstants.WM_ITEM_CONSUME) && layer.get("origin_workorder_id")!=null && !"FINAL".equals(layer.get("status")))throw invalid("自制半成品须先完成成本结算，才能投入下一工单");
                BigDecimal materialAmount=null,laborAmount=null,overheadAmount=null;
                if("FINAL".equals(layer.get("status")) && layer.get("unit_cost")!=null) {
                    if(layer.get("origin_workorder_id")==null){materialAmount=take.multiply(decimal(layer.get("unit_cost"))).setScale(8,RoundingMode.HALF_UP);laborAmount=BigDecimal.ZERO;overheadAmount=BigDecimal.ZERO;}
                    else {
                        Map<String,Object> origin=one("SELECT actual_material_cost,actual_labor_cost,actual_overhead_cost,produced_quantity FROM mes_cost_workorder WHERE tenant_id=? AND workorder_id=? AND deleted=0 AND status='SETTLED' FOR UPDATE",t,layer.get("origin_workorder_id"));
                        if(origin==null || decimal(origin.get("produced_quantity")).signum()<=0)throw invalid("半成品成本来源不完整");
                        BigDecimal[] parts=allocateComposition(take,decimal(layer.get("unit_cost")),origin);
                        materialAmount=parts[0];laborAmount=parts[1];overheadAmount=parts[2];
                    }
                }
                jdbc.update("INSERT INTO mes_cost_allocation(tenant_id,transaction_id,layer_id,quantity,unit_cost,status,material_amount,labor_amount,overhead_amount) VALUES(?,?,?,?,?,?,?,?,?)",t,transaction.getId(),layer.get("id"),take,layer.get("unit_cost"),layer.get("status"),materialAmount,laborAmount,overheadAmount);
                jdbc.update("UPDATE mes_cost_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND tenant_id=? AND deleted=0",take,layer.get("id"),t);
                remaining=remaining.subtract(take);
            }
            if(remaining.signum()>0)throw invalid("库存数量与成本层不一致，事务已回滚");
        } else if(transaction.getRelatedTransactionId()!=null) {
            List<Map<String,Object>> allocations=jdbc.queryForList("SELECT a.quantity,l.unit_cost,l.status,l.origin_workorder_id FROM mes_cost_allocation a JOIN mes_cost_stock_layer l ON l.id=a.layer_id AND l.tenant_id=a.tenant_id AND l.deleted=0 WHERE a.tenant_id=? AND a.transaction_id=? AND a.deleted=0 ORDER BY a.id FOR UPDATE",t,transaction.getRelatedTransactionId());
            BigDecimal copied=BigDecimal.ZERO;
            for(Map<String,Object> a:allocations){BigDecimal q=decimal(a.get("quantity"));insertLayer(stockBefore.getId(),transaction.getId(),id(a.get("origin_workorder_id")),q,decimal(a.get("unit_cost")),(String)a.get("status"));copied=copied.add(q);}
            if(copied.compareTo(transaction.getQuantity())!=0)throw invalid("调拨成本关联数量不一致，事务已回滚");
        } else if(Objects.equals(transaction.getBizType(),MesBizTypeConstants.WM_ITEM_RECEIPT_IN)) {
            Map<String,Object> price=one("SELECT unit_price FROM mes_cost_line_price WHERE tenant_id=? AND kind='RECEIPT' AND line_id=? AND deleted=0 FOR UPDATE",t,transaction.getBizLineId());
            insertLayer(stockBefore.getId(),transaction.getId(),null,transaction.getQuantity(),price==null?null:decimal(price.get("unit_price")),price==null?"INCOMPLETE":"FINAL");
        } else if(Objects.equals(transaction.getBizType(),MesBizTypeConstants.WM_PRODUCT_PRODUCE)) {
            assertWorkOrderMutable(transaction.getWorkOrderId());
            insertLayer(stockBefore.getId(),transaction.getId(),transaction.getWorkOrderId(),transaction.getQuantity(),null,"PROVISIONAL");
        } else insertLayer(stockBefore.getId(),transaction.getId(),null,transaction.getQuantity(),null,"INCOMPLETE");
    }
    private void insertLayer(Long stockId,Long transactionId,Long originWorkorder,BigDecimal quantity,BigDecimal unitCost,String status) {
        jdbc.update("INSERT INTO mes_cost_stock_layer(tenant_id,material_stock_id,inbound_transaction_id,origin_workorder_id,quantity,remaining_quantity,unit_cost,status) VALUES(?,?,?,?,?,?,?,?)",tenant(),stockId,transactionId,originWorkorder,quantity,quantity,unitCost,status);
    }
    private BigDecimal[] allocateComposition(BigDecimal take,BigDecimal unitCost,Map<String,Object> origin) {
        BigDecimal denominator=decimal(origin.get("produced_quantity"));
        BigDecimal[] source={decimal(origin.get("actual_material_cost")),decimal(origin.get("actual_labor_cost")),decimal(origin.get("actual_overhead_cost"))};
        BigDecimal[] parts=new BigDecimal[3];BigDecimal sum=BigDecimal.ZERO;int largest=0;
        for(int i=0;i<3;i++){
            if(source[i]==null || source[i].signum()<0)throw invalid("半成品成本分项无效");
            parts[i]=take.multiply(source[i]).divide(denominator,8,RoundingMode.HALF_UP);sum=sum.add(parts[i]);
            if(source[i].compareTo(source[largest])>0)largest=i;
        }
        BigDecimal residual=take.multiply(unitCost).setScale(8,RoundingMode.HALF_UP).subtract(sum);
        if(residual.signum()!=0){
            if(source[largest].signum()==0 || parts[largest].add(residual).signum()<0)throw invalid("半成品成本尾差超过可分摊范围");
            parts[largest]=parts[largest].add(residual);
        }
        return parts;
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Sale sales(Long salesId) {
        if(one("SELECT id FROM mes_wm_product_sales WHERE id=? AND tenant_id=? AND deleted=0",salesId,tenant())==null)throw invalid("销售出库单不存在或无权访问");
        Sale result=new Sale();result.setSalesId(salesId);result.setQuantity(BigDecimal.ZERO);BigDecimal revenue=BigDecimal.ZERO,cost=BigDecimal.ZERO;boolean complete=true;
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT l.id,l.item_id,l.quantity,i.code,i.name FROM mes_wm_product_sales_line l JOIN mes_md_item i ON i.id=l.item_id AND i.tenant_id=l.tenant_id AND i.deleted=0 WHERE l.sales_id=? AND l.tenant_id=? AND l.deleted=0 ORDER BY l.id",salesId,tenant());
        for(Map<String,Object> row:rows) {
            SaleLine detail=new SaleLine();detail.setLineId(id(row.get("id")));detail.setItemId(id(row.get("item_id")));detail.setItemCode((String)row.get("code"));detail.setItemName((String)row.get("name"));detail.setQuantity(decimal(row.get("quantity")));
            LinePrice price=linePrice("SALES",detail.getLineId());if(price.getUnitPrice()!=null){detail.setUnitPrice(price.getUnitPrice());detail.setSalesAmount(money(price.getUnitPrice().multiply(detail.getQuantity())));}else detail.getIssues().add("缺少实际成交单价");
            Map<String,Object> valuation=one("SELECT SUM(a.quantity) AS quantity,SUM(a.material_amount+a.labor_amount+a.overhead_amount) AS amount,SUM(CASE WHEN a.unit_cost IS NULL OR a.material_amount IS NULL OR a.labor_amount IS NULL OR a.overhead_amount IS NULL OR a.status<>'FINAL' THEN 1 ELSE 0 END) AS missing FROM mes_wm_transaction t JOIN mes_cost_allocation a ON a.transaction_id=t.id AND a.tenant_id=t.tenant_id AND a.deleted=0 WHERE t.tenant_id=? AND t.biz_type=? AND t.biz_id=? AND t.biz_line_id=? AND t.deleted=0 AND t.quantity<0",tenant(),MesBizTypeConstants.WM_PRODUCT_SALES,salesId,detail.getLineId());
            if(valuation==null || decimal(valuation.get("quantity"))==null || decimal(valuation.get("quantity")).compareTo(detail.getQuantity())!=0)detail.getIssues().add("尚未完整执行出库或缺少历史成本分摊");
            else if(nz(decimal(valuation.get("missing"))).signum()>0)detail.getIssues().add("出库时成本尚未确认；保留原始不完整快照");
            else detail.setCostAmount(money(decimal(valuation.get("amount"))));
            if(detail.getSalesAmount()!=null && detail.getCostAmount()!=null){detail.setGrossProfit(money(detail.getSalesAmount().subtract(detail.getCostAmount())));detail.setGrossMargin(ratio(detail.getGrossProfit(),detail.getSalesAmount()));}
            detail.setStatus(detail.getIssues().isEmpty()?"COMPLETE":"INCOMPLETE");if(!detail.getIssues().isEmpty())complete=false;
            result.getLines().add(detail);result.setQuantity(result.getQuantity().add(detail.getQuantity()));revenue=revenue.add(nz(detail.getSalesAmount()));cost=cost.add(nz(detail.getCostAmount()));
        }
        if(rows.isEmpty()){complete=false;result.getIssues().add("销售出库单没有明细");}
        result.setStatus(complete?"COMPLETE":"INCOMPLETE");
        if(result.getLines().stream().allMatch(l->l.getSalesAmount()!=null))result.setSalesAmount(money(revenue));
        if(complete){result.setCostAmount(money(cost));result.setGrossProfit(money(revenue.subtract(cost)));result.setGrossMargin(ratio(result.getGrossProfit(),revenue));}
        return result;
    }
}
