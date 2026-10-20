package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Cost-only operations inside the surrounding stock transaction; never locks old shipments or allocations. */
final class MesReturnCostLedger {
    private final JdbcTemplate jdbc;
    MesReturnCostLedger(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Snapshot(String status,BigDecimal unitCost,BigDecimal material,BigDecimal labor,BigDecimal overhead) {}
    private record Part(long key,Long allocationId,BigDecimal quantity,String status,BigDecimal unit,
                        BigDecimal material,BigDecimal labor,BigDecimal overhead,String issue) {
        boolean complete() { return "FINAL".equals(status)&&nonnegative(unit)&&nonnegative(material)&&nonnegative(labor)&&nonnegative(overhead); }
    }
    private static long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private static ServiceException invalid(String message) { return new ServiceException(1_023_900_001,message); }
    private static BigDecimal n(Object value) { return value==null?null:new BigDecimal(value.toString()); }
    private static Long id(Object value) { return value==null?null:((Number)value).longValue(); }
    private static String text(Object value) { return value==null?null:value.toString(); }
    private static String code(Object value) { return value==null||value.toString().isBlank()?null:value.toString(); }
    private static boolean nonnegative(BigDecimal value) { return value!=null&&value.signum()>=0; }
    private static boolean requiresBasis(Map<String,Object> layer) {
        Object value=layer.get("return_basis_required");return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()!=0;
    }
    private static boolean sameNumber(Object left,Object right) { return left==null?right==null:right!=null&&n(left).compareTo(n(right))==0; }
    private static BigDecimal sum(BigDecimal a,BigDecimal b,BigDecimal c) { return a==null||b==null||c==null?null:a.add(b).add(c); }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args); return rows.isEmpty()?null:rows.get(0);
    }

    /** The business service already owns return parent and dedicated source-budget mutex, before stock. */
    void restore(MesWmTransactionDO tx) {
        if(tx.getReturnSourceTransactionId()==null||tx.getQuantity().signum()<=0||!Objects.equals(tx.getBizType(),119)||tx.getRelatedTransactionId()!=null)
            throw invalid("销售退货成本必须关联明确的原销售出库流水，不能使用成对调拨关联");
        // This is the NEW inbound ledger row, not the original 118 row.
        var persisted=one("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND id=? AND biz_type=119 AND deleted=0 FOR UPDATE",tenant(),tx.getId());
        if(persisted==null||!Objects.equals(id(persisted.get("return_source_transaction_id")),tx.getReturnSourceTransactionId())
                ||!Objects.equals(id(persisted.get("biz_id")),tx.getBizId())||!Objects.equals(id(persisted.get("biz_line_id")),tx.getBizLineId())
                ||!Objects.equals(id(persisted.get("item_id")),tx.getItemId())||!Objects.equals(id(persisted.get("material_stock_id")),tx.getMaterialStockId())
                ||!Objects.equals(id(persisted.get("batch_id")),tx.getBatchId())||!Objects.equals(code(persisted.get("batch_code")),code(tx.getBatchCode()))
                ||!sameNumber(persisted.get("quantity"),tx.getQuantity())||!Objects.equals(id(persisted.get("type")),1L))
            throw invalid("销售退货成本流水与已验证入库不一致");
        var identity=one("SELECT return_id FROM mes_wm_return_sales_line WHERE tenant_id=? AND id=? AND deleted=0",tenant(),tx.getBizLineId());
        if(identity==null||!Objects.equals(id(identity.get("return_id")),tx.getBizId()))throw invalid("退货成本行归属不一致");
        // These own return rows were locked before stock by every 119 preflight entry point.
        var line=one("SELECT l.*,r.client_id,r.status AS return_status FROM mes_wm_return_sales_line l JOIN mes_wm_return_sales r ON r.id=l.return_id AND r.tenant_id=l.tenant_id AND r.deleted=0 WHERE l.tenant_id=? AND l.id=? AND l.return_id=? AND l.deleted=0 FOR UPDATE",tenant(),tx.getBizLineId(),tx.getBizId());
        var origin=origin(tx.getReturnSourceTransactionId());
        if(line==null||origin==null||!Objects.equals(id(line.get("return_id")),tx.getBizId())
                ||!Objects.equals(id(line.get("origin_transaction_id")),tx.getReturnSourceTransactionId())
                ||!Objects.equals(id(line.get("item_id")),tx.getItemId())||!Objects.equals(id(line.get("batch_id")),tx.getBatchId())
                ||!Objects.equals(code(line.get("batch_code")),code(tx.getBatchCode()))
                ||!Objects.equals(id(line.get("client_id")),id(origin.get("client_id")))
                ||!Objects.equals(id(origin.get("item_id")),tx.getItemId())||!Objects.equals(id(origin.get("batch_id")),tx.getBatchId())
                ||!Objects.equals(code(origin.get("batch_code")),code(tx.getBatchCode()))
                ||n(line.get("quantity"))==null||n(line.get("quantity")).compareTo(tx.getQuantity())<0
                ||!Set.of(3L,4L).contains(id(line.get("return_status"))))
            throw invalid("退货成本缺少同客户、物料及批次的实际原销售出库依据");
        var existing=jdbc.queryForList("SELECT * FROM mes_cost_return_allocation WHERE tenant_id=? AND return_transaction_id=? ORDER BY source_part_key",tenant(),tx.getId());
        if(!existing.isEmpty()) {
            BigDecimal recorded=BigDecimal.ZERO;
            for(var row:existing) {
                if(!Objects.equals(id(row.get("source_transaction_id")),tx.getReturnSourceTransactionId()))throw invalid("退货成本重复请求内容不一致");
                recorded=recorded.add(n(row.get("quantity")));
            }
            if(recorded.compareTo(tx.getQuantity())!=0)throw invalid("退货成本重复请求数量不一致");
            return;
        }
        var parts=parts(origin);
        // PK current reads, never SUM from an older RR snapshot. All source parts use a stable order.
        List<Map<String,Object>> balances=new ArrayList<>();
        for(Part part:parts) balances.add(balance(tx.getReturnSourceTransactionId(),part));
        BigDecimal remaining=tx.getQuantity();
        for(int index=0;index<parts.size()&&remaining.signum()>0;index++) {
            Part part=parts.get(index); var balance=balances.get(index);
            BigDecimal before=n(balance.get("returned_quantity"));
            BigDecimal take=remaining.min(part.quantity.subtract(before));
            if(take.signum()<=0)continue;
            BigDecimal after=before.add(take);
            BigDecimal material=part.complete()?portion(part.material,part.quantity,after,n(balance.get("returned_material_amount"))):null;
            BigDecimal labor=part.complete()?portion(part.labor,part.quantity,after,n(balance.get("returned_labor_amount"))):null;
            BigDecimal overhead=part.complete()?portion(part.overhead,part.quantity,after,n(balance.get("returned_overhead_amount"))):null;
            String status=part.complete()?"FINAL":"INCOMPLETE";
            long layer=insertLayer(tx.getMaterialStockId(),tx.getId(),take,part.complete()?part.unit:null,status);
            insertBasis(layer,take,material,labor,overhead,status,tx.getId(),null,part.issue);
            jdbc.update("INSERT INTO mes_cost_return_allocation(tenant_id,source_transaction_id,source_part_key,original_allocation_id,return_transaction_id,restored_layer_id,quantity,original_status,original_unit_cost,original_quantity,original_material_amount,original_labor_amount,original_overhead_amount,material_amount,labor_amount,overhead_amount,status,basis_issue) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenant(),tx.getReturnSourceTransactionId(),part.key,part.allocationId,tx.getId(),layer,take,part.status,part.unit,part.quantity,part.material,part.labor,part.overhead,material,labor,overhead,status,part.issue);
            jdbc.update("UPDATE mes_cost_return_source_balance SET returned_quantity=?,returned_material_amount=?,returned_labor_amount=?,returned_overhead_amount=? WHERE tenant_id=? AND source_transaction_id=? AND source_part_key=?",
                    after,part.complete()?n(balance.get("returned_material_amount")).add(material):null,
                    part.complete()?n(balance.get("returned_labor_amount")).add(labor):null,
                    part.complete()?n(balance.get("returned_overhead_amount")).add(overhead):null,
                    tenant(),tx.getReturnSourceTransactionId(),part.key);
            remaining=remaining.subtract(take);
        }
        if(remaining.signum()!=0)throw invalid("累计退回成本数量超过原销售实际出库，事务已回滚");
    }

    private Map<String,Object> origin(Long transactionId) {
        return one("""
                SELECT t.*,s.id AS sales_id,s.code AS sales_code,s.client_id,l.id AS sales_line_id
                FROM mes_wm_transaction t
                JOIN mes_wm_product_sales s ON s.id=t.biz_id AND s.tenant_id=t.tenant_id AND s.deleted=0 AND s.status=4
                JOIN mes_wm_product_sales_line l ON l.id=t.biz_line_id AND l.sales_id=s.id AND l.tenant_id=t.tenant_id AND l.deleted=0 AND l.item_id=t.item_id
                WHERE t.tenant_id=? AND t.id=? AND t.biz_type=118 AND t.type=2 AND t.quantity<0 AND t.deleted=0
                """,tenant(),transactionId);
    }
    private List<Part> parts(Map<String,Object> origin) {
        var rows=jdbc.queryForList("SELECT * FROM mes_cost_allocation WHERE tenant_id=? AND transaction_id=? AND deleted=0 ORDER BY id",tenant(),origin.get("id"));
        List<Part> parts=new ArrayList<>(); BigDecimal total=BigDecimal.ZERO;
        for(var row:rows) {
            BigDecimal quantity=n(row.get("quantity"));
            if(quantity==null||quantity.signum()<=0) { parts.clear();break; }
            total=total.add(quantity);
            Part part=new Part(id(row.get("id")),id(row.get("id")),quantity,text(row.get("status")),n(row.get("unit_cost")),n(row.get("material_amount")),n(row.get("labor_amount")),n(row.get("overhead_amount")),null);
            parts.add(part.complete()?part:new Part(part.key,part.allocationId,part.quantity,part.status,part.unit,part.material,part.labor,part.overhead,"原出库时成本未确认，保留原始不完整证据"));
        }
        BigDecimal actual=n(origin.get("quantity")).negate();
        if(parts.isEmpty()||total.compareTo(actual)!=0)
            return List.of(new Part(0,null,actual,"UNKNOWN",null,null,null,null,"原出库成本分摊缺失或数量不守恒，不能推定历史成本"));
        return parts;
    }
    private Map<String,Object> balance(Long source,Part part) {
        BigDecimal zero=part.complete()?BigDecimal.ZERO:null;
        jdbc.update("INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,original_allocation_id,source_quantity,source_status,source_unit_cost,source_material_amount,source_labor_amount,source_overhead_amount,returned_material_amount,returned_labor_amount,returned_overhead_amount,basis_issue) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_part_key=VALUES(source_part_key)",
                tenant(),source,part.key,part.allocationId,part.quantity,part.status,part.unit,part.material,part.labor,part.overhead,zero,zero,zero,part.issue);
        var row=one("SELECT * FROM mes_cost_return_source_balance WHERE tenant_id=? AND source_transaction_id=? AND source_part_key=? FOR UPDATE",tenant(),source,part.key);
        if(row==null||!sameNumber(row.get("source_quantity"),part.quantity)||!Objects.equals(text(row.get("source_status")),part.status)
                ||!Objects.equals(id(row.get("original_allocation_id")),part.allocationId)||!sameNumber(row.get("source_unit_cost"),part.unit)
                ||!sameNumber(row.get("source_material_amount"),part.material)||!sameNumber(row.get("source_labor_amount"),part.labor)||!sameNumber(row.get("source_overhead_amount"),part.overhead))
            throw invalid("原销售成本依据发生变化，禁止覆盖已有退货核算");
        return row;
    }
    /** Cumulative rounding ensures that the last portion receives the exact original remainder. */
    private static BigDecimal portion(BigDecimal total,BigDecimal quantity,BigDecimal cumulative,BigDecimal prior) {
        if(total==null||prior==null||quantity.signum()<=0||cumulative.signum()<0||cumulative.compareTo(quantity)>0)throw invalid("成本分项累计依据无效");
        BigDecimal target=cumulative.compareTo(quantity)==0?total:total.multiply(cumulative).divide(quantity,8,RoundingMode.HALF_UP);
        BigDecimal amount=target.subtract(prior);
        if(amount.signum()<0)throw invalid("成本分项累计金额不守恒");
        return amount;
    }

    Snapshot allocateFromLayer(Map<String,Object> layer,BigDecimal take) {
        // The layer itself was read with FOR UPDATE. Never lock an absent basis gap for ordinary FIFO.
        if(!requiresBasis(layer))return null;
        var basis=one("SELECT * FROM mes_cost_layer_basis WHERE tenant_id=? AND layer_id=? FOR UPDATE",tenant(),layer.get("id"));
        if(basis==null)throw invalid("退回库存缺少必须的分项成本依据，事务已回滚");
        BigDecimal quantity=n(basis.get("basis_quantity")),before=n(basis.get("consumed_quantity")),after=before.add(take);
        if(!sameNumber(layer.get("quantity"),quantity)||n(layer.get("remaining_quantity")).add(before).compareTo(quantity)!=0||after.compareTo(quantity)>0
                ||layer.get("origin_workorder_id")!=null)throw invalid("退回库存成本层数量或来源不守恒");
        boolean complete="FINAL".equals(basis.get("status"))&&"FINAL".equals(layer.get("status"))&&layer.get("unit_cost")!=null
                &&nonnegative(n(basis.get("material_amount")))&&nonnegative(n(basis.get("labor_amount")))&&nonnegative(n(basis.get("overhead_amount")));
        BigDecimal material=complete?portion(n(basis.get("material_amount")),quantity,after,n(basis.get("consumed_material_amount"))):null;
        BigDecimal labor=complete?portion(n(basis.get("labor_amount")),quantity,after,n(basis.get("consumed_labor_amount"))):null;
        BigDecimal overhead=complete?portion(n(basis.get("overhead_amount")),quantity,after,n(basis.get("consumed_overhead_amount"))):null;
        jdbc.update("UPDATE mes_cost_layer_basis SET consumed_quantity=?,consumed_material_amount=?,consumed_labor_amount=?,consumed_overhead_amount=? WHERE tenant_id=? AND layer_id=?",
                after,complete?n(basis.get("consumed_material_amount")).add(material):null,complete?n(basis.get("consumed_labor_amount")).add(labor):null,complete?n(basis.get("consumed_overhead_amount")).add(overhead):null,tenant(),layer.get("id"));
        return new Snapshot(complete?"FINAL":"INCOMPLETE",complete?n(layer.get("unit_cost")):null,material,labor,overhead);
    }
    boolean transfer(Map<String,Object> allocation,Long stockId,Long inboundTransaction) {
        if(!requiresBasis(allocation))return false;
        var basis=one("SELECT * FROM mes_cost_layer_basis WHERE tenant_id=? AND layer_id=? FOR UPDATE",tenant(),allocation.get("layer_id"));
        if(basis==null)throw invalid("退回库存调拨缺少必须的分项成本依据，事务已回滚");
        BigDecimal material=n(allocation.get("material_amount")),labor=n(allocation.get("labor_amount")),overhead=n(allocation.get("overhead_amount"));
        boolean complete="FINAL".equals(basis.get("status"))&&"FINAL".equals(allocation.get("snapshot_status"))&&allocation.get("snapshot_unit_cost")!=null&&nonnegative(material)&&nonnegative(labor)&&nonnegative(overhead);
        String status=complete?"FINAL":"INCOMPLETE"; BigDecimal quantity=n(allocation.get("quantity"));
        long layer=insertLayer(stockId,inboundTransaction,quantity,complete?n(allocation.get("snapshot_unit_cost")):null,status);
        insertBasis(layer,quantity,complete?material:null,complete?labor:null,complete?overhead:null,status,id(basis.get("origin_return_transaction_id")),id(allocation.get("allocation_id")),text(basis.get("basis_issue")));
        return true;
    }
    private long insertLayer(Long stockId,Long transactionId,BigDecimal quantity,BigDecimal unit,String status) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO mes_cost_stock_layer(tenant_id,material_stock_id,inbound_transaction_id,origin_workorder_id,quantity,remaining_quantity,unit_cost,status,return_basis_required) VALUES(?,?,?,NULL,?,?,?,?,1)",new String[]{"id"});
            Object[] values={tenant(),stockId,transactionId,quantity,quantity,unit,status};
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]); return statement;
        },key);
        if(key.getKey()==null)throw invalid("退回成本层创建失败");
        return key.getKey().longValue();
    }
    private void insertBasis(long layer,BigDecimal quantity,BigDecimal material,BigDecimal labor,BigDecimal overhead,String status,Long originReturn,Long sourceAllocation,String issue) {
        BigDecimal zero="FINAL".equals(status)?BigDecimal.ZERO:null;
        jdbc.update("INSERT INTO mes_cost_layer_basis(tenant_id,layer_id,basis_quantity,material_amount,labor_amount,overhead_amount,consumed_material_amount,consumed_labor_amount,consumed_overhead_amount,status,origin_return_transaction_id,source_allocation_id,basis_issue) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenant(),layer,quantity,material,labor,overhead,zero,zero,zero,status,originReturn,sourceAllocation,issue);
    }

    ReturnSale returnSales(Long returnId) {
        var header=one("SELECT * FROM mes_wm_return_sales WHERE tenant_id=? AND id=? AND deleted=0",tenant(),returnId);
        if(header==null)throw invalid("销售退货单不存在或无权访问");
        ReturnSale result=new ReturnSale();result.setReturnId(returnId);result.setReturnCode(text(header.get("code")));result.setPosted(Objects.equals(id(header.get("status")),4L));
        var lines=jdbc.queryForList("SELECT l.*,i.code AS item_code,i.name AS item_name FROM mes_wm_return_sales_line l LEFT JOIN mes_md_item i ON i.id=l.item_id AND i.tenant_id=l.tenant_id AND i.deleted=0 WHERE l.tenant_id=? AND l.return_id=? AND l.deleted=0 ORDER BY l.id",tenant(),returnId);
        Set<Long> seen=new HashSet<>();BigDecimal material=BigDecimal.ZERO,labor=BigDecimal.ZERO,overhead=BigDecimal.ZERO;
        for(var row:lines) {
            ReturnSaleLine line=new ReturnSaleLine();line.setLineId(id(row.get("id")));line.setItemId(id(row.get("item_id")));line.setItemCode(text(row.get("item_code")));line.setItemName(text(row.get("item_name")));line.setQuantity(n(row.get("quantity")));line.setOriginTransactionId(id(row.get("origin_transaction_id")));
            var source=line.getOriginTransactionId()==null?null:origin(line.getOriginTransactionId());
            if(source==null||!Objects.equals(id(source.get("client_id")),id(header.get("client_id")))
                    ||!Objects.equals(id(source.get("item_id")),line.getItemId())||!Objects.equals(id(source.get("batch_id")),id(row.get("batch_id")))
                    ||!Objects.equals(code(source.get("batch_code")),code(row.get("batch_code"))))
                line.getIssues().add("缺少同客户、物料及批次的原销售出库成本依据");
            else {
                line.setOriginSalesId(id(source.get("sales_id")));line.setOriginSalesCode(text(source.get("sales_code")));line.setOriginSalesLineId(id(source.get("sales_line_id")));
                if(seen.add(line.getOriginTransactionId()))result.getOriginals().add(originalSummary(source));
            }
            BigDecimal posted=BigDecimal.ZERO,covered=BigDecimal.ZERO,m=BigDecimal.ZERO,l=BigDecimal.ZERO,o=BigDecimal.ZERO;
            var transactions=jdbc.queryForList("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND biz_id=? AND biz_line_id=? AND deleted=0 ORDER BY id",tenant(),returnId,line.getLineId());
            for(var tx:transactions) {
                BigDecimal q=n(tx.get("quantity"));
                if(q==null||q.signum()<=0||!Objects.equals(id(tx.get("return_source_transaction_id")),line.getOriginTransactionId())||!Objects.equals(id(tx.get("item_id")),line.getItemId())||!Objects.equals(id(tx.get("batch_id")),id(row.get("batch_id")))) {
                    line.getIssues().add("实际退货流水归属或数量不一致");continue;
                }
                posted=posted.add(q);
                var allocations=jdbc.queryForList("SELECT * FROM mes_cost_return_allocation WHERE tenant_id=? AND return_transaction_id=? ORDER BY id",tenant(),tx.get("id"));
                BigDecimal transactionCovered=BigDecimal.ZERO;
                for(var allocation:allocations) {
                    BigDecimal allocatedQuantity=n(allocation.get("quantity"));
                    if(allocatedQuantity==null||allocatedQuantity.signum()<=0||!Objects.equals(id(allocation.get("source_transaction_id")),line.getOriginTransactionId())) {
                        line.getIssues().add("退货成本分摊的原流水或数量不一致");continue;
                    }
                    covered=covered.add(allocatedQuantity);transactionCovered=transactionCovered.add(allocatedQuantity);
                    var restored=one("SELECT l.quantity,b.basis_quantity FROM mes_cost_stock_layer l JOIN mes_cost_layer_basis b ON b.tenant_id=l.tenant_id AND b.layer_id=l.id WHERE l.tenant_id=? AND l.id=? AND l.inbound_transaction_id=? AND l.material_stock_id=? AND l.origin_workorder_id IS NULL AND l.deleted=0",tenant(),allocation.get("restored_layer_id"),tx.get("id"),tx.get("material_stock_id"));
                    if(restored==null||!sameNumber(restored.get("quantity"),allocatedQuantity)||!sameNumber(restored.get("basis_quantity"),allocatedQuantity))line.getIssues().add("退货成本分摊缺少对应的实际成本层");
                    if(!"FINAL".equals(allocation.get("status"))||allocation.get("material_amount")==null||allocation.get("labor_amount")==null||allocation.get("overhead_amount")==null)
                        line.getIssues().add("原出库时成本不完整，退回成本保留待核定");
                    else {m=m.add(n(allocation.get("material_amount")));l=l.add(n(allocation.get("labor_amount")));o=o.add(n(allocation.get("overhead_amount")));}
                }
                if(transactionCovered.compareTo(q)!=0)line.getIssues().add("单笔退货流水与成本分摊数量不守恒");
            }
            line.setPostedQuantity(posted);
            if(!Boolean.TRUE.equals(result.getPosted())||posted.compareTo(line.getQuantity())!=0||covered.compareTo(posted)!=0||posted.signum()==0)
                line.getIssues().add("尚未完整实际入库或缺少退货成本分摊");
            line.setStatus(line.getIssues().isEmpty()?"COMPLETE":"INCOMPLETE");
            if(line.getIssues().isEmpty()) {line.setMaterialAmount(m);line.setLaborAmount(l);line.setOverheadAmount(o);line.setCostAmount(sum(m,l,o));material=material.add(m);labor=labor.add(l);overhead=overhead.add(o);}
            result.getLines().add(line);
        }
        if(lines.isEmpty())result.getIssues().add("销售退货单没有明细");
        boolean complete=!lines.isEmpty()&&result.getLines().stream().allMatch(line->"COMPLETE".equals(line.getStatus()));
        result.setStatus(complete?"COMPLETE":"INCOMPLETE");
        if(complete) {result.setMaterialAmount(material);result.setLaborAmount(labor);result.setOverheadAmount(overhead);result.setCostAmount(sum(material,labor,overhead));}
        return result;
    }
    private ReturnSaleOriginal originalSummary(Map<String,Object> origin) {
        ReturnSaleOriginal result=new ReturnSaleOriginal();result.setOriginTransactionId(id(origin.get("id")));result.setSalesId(id(origin.get("sales_id")));result.setSalesCode(text(origin.get("sales_code")));result.setSalesLineId(id(origin.get("sales_line_id")));result.setItemId(id(origin.get("item_id")));
        var item=one("SELECT code,name FROM mes_md_item WHERE tenant_id=? AND id=? AND deleted=0",tenant(),origin.get("item_id"));
        if(item!=null){result.setItemCode(text(item.get("code")));result.setItemName(text(item.get("name")));}
        result.setOutboundQuantity(n(origin.get("quantity")).negate());
        BigDecimal returned=BigDecimal.ZERO,originalCost=BigDecimal.ZERO,returnedCost=BigDecimal.ZERO; boolean complete=true,originalComplete=true;
        for(Part part:parts(origin)) {
            if(!part.complete()){complete=false;originalComplete=false;}
            else originalCost=originalCost.add(sum(part.material,part.labor,part.overhead));
            var balance=one("SELECT * FROM mes_cost_return_source_balance WHERE tenant_id=? AND source_transaction_id=? AND source_part_key=?",tenant(),origin.get("id"),part.key);
            if(balance!=null) {
                returned=returned.add(n(balance.get("returned_quantity")));
                BigDecimal amount=sum(n(balance.get("returned_material_amount")),n(balance.get("returned_labor_amount")),n(balance.get("returned_overhead_amount")));
                if(amount==null)complete=false;else returnedCost=returnedCost.add(amount);
            }
        }
        var actual=one("SELECT SUM(quantity) AS quantity,SUM(CASE WHEN deleted<>0 THEN 1 ELSE 0 END) AS deleted_count FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND return_source_transaction_id=? AND quantity>0",tenant(),origin.get("id"));
        BigDecimal actualReturned=actual==null||actual.get("quantity")==null?BigDecimal.ZERO:n(actual.get("quantity"));
        if(originalComplete)result.setOriginalCostAmount(originalCost);
        if(hasUnknownLegacyReturn(origin)) {
            result.setStatus("INCOMPLETE");
            result.getIssues().add("同客户、物料或批次存在未关联原出库的历史退货，累计已退和剩余数量/成本无法核定；不得将未知显示为零");
            return result;
        }
        if(actualReturned.compareTo(returned)!=0||returned.compareTo(result.getOutboundQuantity())>0||actual!=null&&actual.get("deleted_count")!=null&&n(actual.get("deleted_count")).signum()>0)complete=false;
        result.setReturnedQuantity(actualReturned);result.setRemainingQuantity(actualReturned.compareTo(result.getOutboundQuantity())<=0?result.getOutboundQuantity().subtract(actualReturned):null);
        result.setStatus(complete?"COMPLETE":"INCOMPLETE");
        if(complete) {result.setReturnedCostAmount(returnedCost);result.setRemainingCostAmount(originalCost.subtract(returnedCost));}
        else result.getIssues().add("原销售或累计退回成本证据不完整，不能推定剩余成本");
        return result;
    }
    private boolean hasUnknownLegacyReturn(Map<String,Object> origin) {
        Object client=origin.get("client_id"),item=origin.get("item_id"),batch=origin.get("batch_id");
        var unknown=one("""
                SELECT t.id FROM mes_wm_transaction t
                LEFT JOIN mes_wm_return_sales r ON r.id=t.biz_id AND r.tenant_id=t.tenant_id
                LEFT JOIN mes_wm_return_sales_line l ON l.id=t.biz_line_id AND l.return_id=t.biz_id AND l.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.biz_type=119 AND t.return_source_transaction_id IS NULL
                  AND (r.id IS NULL OR l.id IS NULL OR r.client_id IS NULL OR t.item_id IS NULL
                    OR (r.client_id=? AND t.item_id=? AND (t.batch_id=? OR (t.batch_id IS NULL AND ? IS NULL))))
                LIMIT 1
                """,tenant(),client,item,batch,batch);
        if(unknown!=null)return true;
        return one("""
                SELECT r.id FROM mes_wm_return_sales r LEFT JOIN mes_wm_return_sales_line l ON l.return_id=r.id AND l.tenant_id=r.tenant_id
                WHERE r.tenant_id=? AND r.status=4 AND (l.id IS NULL OR l.origin_transaction_id IS NULL)
                  AND (r.client_id IS NULL OR l.id IS NULL OR l.item_id IS NULL
                    OR (r.client_id=? AND l.item_id=? AND (l.batch_id=? OR (l.batch_id IS NULL AND ? IS NULL))))
                LIMIT 1
                """,tenant(),client,item,batch,batch)!=null;
    }
}
