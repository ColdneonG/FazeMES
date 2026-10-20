package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;

/** Fixed, parameter-bound, physical inventory queries. No model supplied SQL or arithmetic. */
@Service
public class MesInsightInventoryQuery {
    public static final String STOCK_PERMISSION = "mes:wm-material-stock:query";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ROWS = 5000;
    private static final int MAX_POSITIONS = 50000;
    private final JdbcTemplate jdbc;
    private final SecurityFrameworkService security;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    @Autowired
    public MesInsightInventoryQuery(JdbcTemplate jdbc, SecurityFrameworkService security,
                                    PlatformTransactionManager transactions) {
        this(jdbc, security, transactions, Clock.system(ZONE));
    }
    MesInsightInventoryQuery(JdbcTemplate jdbc, SecurityFrameworkService security,
                            PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc; this.security = security; this.clock = clock;
        snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static class ClarificationException extends RuntimeException {
        private final String reason;
        private final String prompt;
        private final List<Map<String,Object>> choices;
        private final List<String> requiredPermissions;
        public ClarificationException(String reason, String prompt, List<Map<String,Object>> choices) {
            this(reason,prompt,choices,List.of(STOCK_PERMISSION));
        }
        public ClarificationException(String reason, String prompt, List<Map<String,Object>> choices, List<String> requiredPermissions) {
            super(prompt); this.reason=reason; this.prompt=prompt; this.choices=choices; this.requiredPermissions=requiredPermissions;
        }
        public String getReason() { return reason; }
        public String getPrompt() { return prompt; }
        public List<Map<String,Object>> getChoices() { return choices; }
        public List<String> getRequiredPermissions() { return requiredPermissions; }
    }
    private static ClarificationException narrow() {
        return new ClarificationException("SCOPE_TOO_LARGE", "结果超过5000行，请指定物料、分类、仓库或批次后再查询", List.of());
    }
    private <T> T read(Supplier<T> action) {
        if (!security.hasPermission(STOCK_PERMISSION)) throw invalid("查询库存事实需要库存台账查询权限");
        TenantContextHolder.getRequiredTenantId();
        return snapshot.execute(status -> {
            // Even an empty InnoDB consistent table read establishes the RR read view.
            jdbc.queryForList("SELECT id FROM mes_wm_material_stock WHERE tenant_id=? LIMIT 1", tenant());
            snapshotEstablished();
            return action.get();
        });
    }
    /** Test seam for proving a posting committed after establishing the read view cannot leak in. */
    protected void snapshotEstablished() { }
    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZONE); }
    private static String iso(LocalDateTime value) { return value == null ? null : value.atZone(ZONE).toOffsetDateTime().toString(); }
    private static Long id(Object value) { return value == null ? null : ((Number)value).longValue(); }
    private static int integer(Object value) { return ((Number)value).intValue(); }
    private static BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private static String quantity(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static LocalDateTime time(Object value) {
        return value instanceof Timestamp t ? t.toLocalDateTime() : value instanceof LocalDateTime t ? t : null;
    }
    private static boolean truth(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0; }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static Set<Long> ids(Collection<Map<String,Object>> rows,String key) {
        Set<Long> result=new LinkedHashSet<>();
        for(var row:rows) { Long id=id(row.get(key)); if(id!=null)result.add(id); }
        return result;
    }
    private static Map<String,Object> warning(String code, String message) { return map("code",code,"message",message); }
    private static void warn(List<Map<String,Object>> warnings, String code, String message) {
        if(warnings.stream().noneMatch(w -> code.equals(w.get("code")))) warnings.add(warning(code,message));
    }
    private Map<Long,Map<String,Object>> master(String table, String extra) {
        return master(table,extra,null);
    }
    private Map<Long,Map<String,Object>> master(String table, String extra, Collection<Long> selectedIds) {
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();
        if(selectedIds!=null&&selectedIds.isEmpty()) return result;
        List<Object> args=new ArrayList<>(); args.add(tenant());
        String filter="";
        if(selectedIds!=null) {
            filter=" AND id IN("+selectedIds.stream().map(x->"?").collect(Collectors.joining(","))+")"; args.addAll(selectedIds);
        }
        var rows=jdbc.queryForList("SELECT id,code,name"+extra+" FROM "+table+" WHERE tenant_id=? AND deleted=0"+filter+" ORDER BY id LIMIT 100001",args.toArray());
        if(rows.size()>100000) throw new ClarificationException("SCOPE_TOO_LARGE","主数据目录超过安全读取范围，请指定单个物料或请管理员核对、整理分类目录后再查询",List.of());
        for(var row:rows)
            result.put(id(row.get("id")),row);
        return result;
    }
    private static Map<String,Object> entity(Long id, Map<Long,Map<String,Object>> master) {
        if(id==null) return null;
        var row=master.get(id);
        return map("id",id,"code",row==null?null:row.get("code"),"name",row==null?null:row.get("name"));
    }
    private static Long checkedId(Map<String,Object> slots, String key, Map<Long,Map<String,Object>> master) {
        Long selected=id(slots.get(key));
        if(selected!=null && !master.containsKey(selected)) throw invalid("筛选资料已失效或无权访问，请重新选择");
        return selected;
    }
    private static Set<Long> descendants(Long selected, Map<Long,Map<String,Object>> types) {
        if(selected==null) return Set.of();
        Map<Long,List<Long>> children=new HashMap<>();
        for(var row:types.values()) children.computeIfAbsent(id(row.get("parent_id")),key->new ArrayList<>()).add(id(row.get("id")));
        Set<Long> result=new LinkedHashSet<>(); ArrayDeque<Long> queue=new ArrayDeque<>(); queue.add(selected);
        while(!queue.isEmpty()) {
            Long current=queue.removeFirst();
            if(!result.add(current)) throw new ClarificationException("NEED_FILTER","物料分类存在循环，请先维护分类关系",List.of());
            if(result.size()>10000) throw narrow();
            queue.addAll(children.getOrDefault(current,List.of()));
        }
        return result;
    }
    private record Filter(Long item, Set<Long> categories, Long warehouse, String batch) {
        String sql(String alias, List<Object> args) {
            StringBuilder s=new StringBuilder(" AND ").append(alias).append(".tenant_id=?");
            args.add(TenantContextHolder.getRequiredTenantId());
            if(item!=null) { s.append(" AND ").append(alias).append(".item_id=?"); args.add(item); }
            if(warehouse!=null) { s.append(" AND ").append(alias).append(".warehouse_id=?"); args.add(warehouse); }
            if(batch!=null) { s.append(" AND ").append(alias).append(".batch_code=?"); args.add(batch); }
            if(!categories.isEmpty()) {
                s.append(" AND EXISTS(SELECT 1 FROM mes_md_item fi WHERE fi.id=").append(alias)
                        .append(".item_id AND fi.tenant_id=").append(alias).append(".tenant_id AND fi.deleted=0 AND fi.item_type_id IN (")
                        .append(categories.stream().map(x->"?").collect(Collectors.joining(","))).append("))");
                args.addAll(categories);
            }
            return s.toString();
        }
    }
    private record Dimension(Long item, Long batch, String batchCode, Long warehouse) { }
    private static Dimension dimension(Map<String,Object> row) {
        return new Dimension(id(row.get("item_id")),id(row.get("batch_id")),text(row.get("batch_code")),id(row.get("warehouse_id")));
    }
    private static String groupKey(Dimension d,String group) {
        // Length framing avoids collisions between user supplied batch codes and IDs.
        String batch=d.batchCode()==null?"":d.batchCode();
        return d.item()+switch(group) {
            case "ITEM" -> "";
            case "WAREHOUSE" -> "/W/"+d.warehouse();
            default -> "/B/"+d.batch()+"/"+batch.length()+":"+batch;
        };
    }
    private static final class Amounts {
        BigDecimal opening=BigDecimal.ZERO,inbound=BigDecimal.ZERO,outbound=BigDecimal.ZERO,closing=BigDecimal.ZERO;
        boolean complete=true;
        final List<Map<String,Object>> warnings=new ArrayList<>();
        static BigDecimal add(BigDecimal a,BigDecimal b) { return a==null||b==null?null:a.add(b); }
        void add(Amounts other) {
            opening=add(opening,other.opening); inbound=add(inbound,other.inbound);
            outbound=add(outbound,other.outbound); closing=add(closing,other.closing);
            complete&=other.complete;
            for(var w:other.warnings) warn(warnings,text(w.get("code")),text(w.get("message")));
        }
        void unknownBalances(String code,String message) { opening=null; closing=null; incomplete(code,message); }
        void incomplete(String code,String message) { complete=false; warn(warnings,code,message); }
        Map<String,Object> fields() {
            Boolean balanced=opening==null||inbound==null||outbound==null||closing==null?null:opening.add(inbound).subtract(outbound).compareTo(closing)==0;
            return map("openingQuantity",quantity(opening),"inboundQuantity",quantity(inbound),"outboundQuantity",quantity(outbound),
                    "closingQuantity",quantity(closing),"balanced",balanced,"complete",complete);
        }
    }
    private record Piece(Dimension dimension, Amounts amounts) { }

    public Map<String,Object> inventory(Map<String,Object> slots) { return read(() -> inventorySnapshot(slots)); }
    private Map<String,Object> inventorySnapshot(Map<String,Object> slots) {
        LocalDateTime asOf=now();
        YearMonth month;
        try { month=YearMonth.of(integer(slots.get("year")),integer(slots.get("month"))); }
        catch(RuntimeException bad) { throw new ClarificationException("INVALID_PERIOD","请指定有效的年份和月份",List.of()); }
        LocalDateTime start=month.atDay(1).atStartOfDay(), end=month.plusMonths(1).atDay(1).atStartOfDay();
        if(start.isAfter(asOf)) throw new ClarificationException("INVALID_PERIOD","未来月份尚无完整库存事实，请选择本月或已结束月份",List.of());
        LocalDateTime effectiveEnd=end.isAfter(asOf)?asOf:end;
        String group=Objects.toString(slots.get("groupBy"),"BATCH");
        if(!Set.of("ITEM","BATCH","WAREHOUSE").contains(group)) throw invalid("不支持的库存分组");
        Long item=id(slots.get("itemId")), category=id(slots.get("categoryId")), warehouse=id(slots.get("warehouseId"));
        var items=master("mes_md_item",",item_type_id,unit_measure_id",item==null?Set.of():Set.of(item));
        var types=category==null?new LinkedHashMap<Long,Map<String,Object>>():master("mes_md_item_type",",parent_id");
        var warehouses=master("mes_wm_warehouse","",warehouse==null?Set.of():Set.of(warehouse));
        checkedId(slots,"itemId",items); checkedId(slots,"categoryId",types); checkedId(slots,"warehouseId",warehouses);
        String batch=text(slots.get("batchCode"));
        Filter filter=new Filter(item,descendants(category,types),warehouse,batch);
        String dims="item_id"+(group.equals("BATCH")?",batch_id,batch_code":group.equals("WAREHOUSE")?",warehouse_id":"");
        List<Object> countArgs=new ArrayList<>();
        String stockFilter=filter.sql("s",countArgs),txFilter=filter.sql("t",countArgs);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT "+dims+" FROM mes_wm_material_stock s WHERE (s.deleted=0 OR s.quantity<>0 OR s.quantity IS NULL)"+stockFilter+
                " GROUP BY "+dims+" UNION SELECT "+dims+" FROM mes_wm_transaction t WHERE 1=1"+txFilter+" GROUP BY "+dims+") scopes",Long.class,countArgs.toArray());
        if(count!=null && count>MAX_ROWS) throw narrow();
        List<Object> stockArgs=new ArrayList<>();
        var stocks=jdbc.queryForList("SELECT id,item_id,batch_id,batch_code,warehouse_id,quantity,create_time,deleted FROM mes_wm_material_stock s WHERE (s.deleted=0 OR s.quantity<>0 OR s.quantity IS NULL)"+
                filter.sql("s",stockArgs)+" ORDER BY id LIMIT 50001",stockArgs.toArray());
        if(stocks.size()>MAX_POSITIONS) throw new ClarificationException("SCOPE_TOO_LARGE","库存位置记录超过50000条，请增加物料、仓库或批次筛选；本次未返回截断合计",List.of());
        Map<Long,Map<String,Object>> stockById=new LinkedHashMap<>();
        for(var s:stocks) stockById.put(id(s.get("id")),s);
        List<Object> txArgs=new ArrayList<>(List.of(Timestamp.valueOf(start),Timestamp.valueOf(asOf),Timestamp.valueOf(effectiveEnd),Timestamp.valueOf(asOf),
                Timestamp.valueOf(start),Timestamp.valueOf(effectiveEnd),Timestamp.valueOf(start),Timestamp.valueOf(effectiveEnd),Timestamp.valueOf(asOf)));
        String aggregates="SUM(CASE WHEN deleted=0 THEN quantity ELSE 0 END) AS history,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time>=? AND transaction_time<=? THEN quantity ELSE 0 END) AS after_start,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time>=? AND transaction_time<=? THEN quantity ELSE 0 END) AS after_end,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time>=? AND transaction_time<? AND quantity>0 THEN quantity ELSE 0 END) AS inbound,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time>=? AND transaction_time<? AND quantity<0 THEN -quantity ELSE 0 END) AS outbound,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time IS NULL THEN 1 ELSE 0 END) AS missing_time,"+
                "SUM(CASE WHEN quantity IS NULL THEN 1 ELSE 0 END) AS missing_quantity,"+
                "SUM(CASE WHEN deleted=0 AND transaction_time>? THEN 1 ELSE 0 END) AS future_time,"+
                "SUM(CASE WHEN deleted<>0 AND quantity<>0 THEN 1 ELSE 0 END) AS deleted_ledger";
        var postings=jdbc.queryForList("SELECT material_stock_id,item_id,batch_id,batch_code,warehouse_id,"+aggregates+
                " FROM mes_wm_transaction t WHERE 1=1"+filter.sql("t",txArgs)+
                " GROUP BY material_stock_id,item_id,batch_id,batch_code,warehouse_id ORDER BY material_stock_id,item_id LIMIT 50001",txArgs.toArray());
        if(postings.size()>MAX_POSITIONS) throw new ClarificationException("SCOPE_TOO_LARGE","流水关联位置超过50000条，请增加物料、仓库或批次筛选；本次未返回截断合计",List.of());
        Map<Long,List<Map<String,Object>>> byStock=new LinkedHashMap<>();
        List<Piece> pieces=new ArrayList<>();
        for(var t:postings) byStock.computeIfAbsent(id(t.get("material_stock_id")),x->new ArrayList<>()).add(t);
        for(var s:stocks) {
            Long stockId=id(s.get("id")); Dimension d=dimension(s); Amounts a=new Amounts();
            BigDecimal balance=decimal(s.get("quantity")); a.opening=balance; a.closing=balance;
            if(s.get("quantity")==null) a.unknownBalances("MISSING_STOCK_QUANTITY","库存数量缺失，不能按零余额处理");
            BigDecimal history=BigDecimal.ZERO;
            for(var t:byStock.getOrDefault(stockId,List.of())) {
                if(!d.equals(dimension(t))) {
                    a.unknownBalances("STOCK_DIMENSION_MISMATCH","库存记录与流水物料、批次或仓库不一致，余额待核对");
                    Amounts detached=postingAmounts(t,BigDecimal.ZERO);
                    detached.unknownBalances("STOCK_DIMENSION_MISMATCH","库存记录与流水维度不一致，余额待核对");
                    pieces.add(new Piece(dimension(t),detached));
                    continue;
                }
                Amounts tx=postingAmounts(t,BigDecimal.ZERO);
                a.opening=Amounts.add(a.opening,tx.opening); a.closing=Amounts.add(a.closing,tx.closing);
                a.inbound=Amounts.add(a.inbound,tx.inbound); a.outbound=Amounts.add(a.outbound,tx.outbound);
                a.complete&=tx.complete; a.warnings.addAll(tx.warnings);
                history=history.add(decimal(t.get("history")));
            }
            if(truth(s.get("deleted")) && balance.signum()!=0) a.unknownBalances("DELETED_STOCK_BALANCE","已删除库存仍有数量，无法可靠还原余额");
            if(balance.subtract(history).signum()!=0) {
                LocalDateTime created=time(s.get("create_time"));
                a.incomplete("IMPORTED_BALANCE","存在无完整原始凭据的导入或期初余额，按当前余额回推");
                if(created==null) a.unknownBalances("MISSING_STOCK_TIME","导入余额缺少首条库存记录时间，无法证明历史余额");
                else {
                    if(created.isAfter(start)) { a.opening=null; a.incomplete("IMPORT_AFTER_OPENING","导入库存晚于月初，期初余额无法证明"); }
                    if(!created.isBefore(effectiveEnd)) { a.closing=null; a.incomplete("IMPORT_AFTER_CLOSING","导入库存不早于统计截止，期末余额无法证明"); }
                }
            }
            pieces.add(new Piece(d,a));
        }
        for(var entry:byStock.entrySet()) if(!stockById.containsKey(entry.getKey())) for(var t:entry.getValue()) {
            Amounts a=postingAmounts(t,BigDecimal.ZERO);
            a.unknownBalances("ORPHAN_STOCK","流水缺少可核对的库存记录，余额未知");
            pieces.add(new Piece(dimension(t),a));
        }
        Map<String,Amounts> grouped=new LinkedHashMap<>(); Map<String,Dimension> dimensions=new LinkedHashMap<>();
        for(var piece:pieces) {
            String key=groupKey(piece.dimension(),group);
            dimensions.putIfAbsent(key,piece.dimension()); grouped.computeIfAbsent(key,k->new Amounts()).add(piece.amounts());
        }
        if(grouped.size()>MAX_ROWS) throw narrow();
        Set<Long> itemIds=new LinkedHashSet<>(),warehouseIds=new LinkedHashSet<>();
        if(item!=null)itemIds.add(item); if(warehouse!=null)warehouseIds.add(warehouse);
        for(var piece:pieces) { var d=piece.dimension(); if(d.item()!=null)itemIds.add(d.item()); if(d.warehouse()!=null)warehouseIds.add(d.warehouse()); }
        items=master("mes_md_item",",item_type_id,unit_measure_id",itemIds);
        Set<Long> unitIds=new LinkedHashSet<>(),typeIds=new LinkedHashSet<>();
        for(var m:items.values()) { Long unitId=id(m.get("unit_measure_id")),typeId=id(m.get("item_type_id")); if(unitId!=null)unitIds.add(unitId); if(typeId!=null)typeIds.add(typeId); }
        var units=master("mes_md_unit_measure","",unitIds);
        if(category==null)types=master("mes_md_item_type",",parent_id",typeIds);
        warehouses=master("mes_wm_warehouse","",warehouseIds);
        for(var piece:pieces) if(!warehouses.containsKey(piece.dimension().warehouse()))
            grouped.get(groupKey(piece.dimension(),group)).incomplete("MISSING_WAREHOUSE","仓库主数据缺失或已删除");
        List<Map<String,Object>> result=new ArrayList<>(),warnings=new ArrayList<>();
        Map<String,Amounts> totalByUnit=new LinkedHashMap<>(); Map<String,Map<String,Object>> unitMetadata=new LinkedHashMap<>();
        Map<String,Integer> unitCounts=new LinkedHashMap<>(); boolean complete=true;
        for(var entry:grouped.entrySet()) {
            var d=dimensions.get(entry.getKey()); var a=entry.getValue(); var m=items.get(d.item());
            Long unitId=m==null?null:id(m.get("unit_measure_id")), typeId=m==null?null:id(m.get("item_type_id"));
            var unit=units.get(unitId); String unitKey=unit==null?"UNKNOWN_ITEM:"+d.item():"UNIT:"+unitId;
            if(m==null) a.incomplete("MISSING_ITEM","物料主数据缺失或已删除，分类与计量单位待核对");
            if(unit==null) a.incomplete("MISSING_UNIT","计量单位缺失，不与其他物料合并数量");
            if(typeId!=null&&!types.containsKey(typeId)) a.incomplete("MISSING_CATEGORY","当前物料分类缺失或已删除");
            if(!warehouses.containsKey(d.warehouse())) a.incomplete("MISSING_WAREHOUSE","仓库主数据缺失或已删除");
            Map<String,Object> row=map("rowKey",entry.getKey(),"item",entity(d.item(),items),"category",entity(typeId,types),
                    "batch",group.equals("BATCH")?map("id",d.batch(),"code",d.batchCode()):null,
                    "warehouse",group.equals("WAREHOUSE")?entity(d.warehouse(),warehouses):null,
                    "unitKey",unitKey,"unitMeasureId",unit==null?null:unitId,"unitName",unit==null?null:unit.get("name"));
            row.putAll(a.fields()); row.put("warnings",a.warnings); result.add(row);
            totalByUnit.computeIfAbsent(unitKey,k->new Amounts()).add(a); unitCounts.merge(unitKey,1,Integer::sum);
            unitMetadata.putIfAbsent(unitKey,map("unitKey",unitKey,"unitMeasureId",unit==null?null:unitId,
                    "unitCode",unit==null?null:unit.get("code"),"unitName",unit==null?null:unit.get("name")));
            complete&=a.complete; for(var w:a.warnings) warn(warnings,text(w.get("code")),text(w.get("message")));
        }
        List<Map<String,Object>> totals=new ArrayList<>();
        for(var e:totalByUnit.entrySet()) { var row=unitMetadata.get(e.getKey()); row.putAll(e.getValue().fields()); row.put("rowCount",unitCounts.get(e.getKey())); totals.add(row); }
        return map("kind","INVENTORY_LEDGER","asOf",iso(asOf),"period",map("year",month.getYear(),"month",month.getMonthValue(),"start",iso(start),
                        "endExclusive",iso(end),"effectiveEndExclusive",iso(effectiveEnd),"partialMonth",end.isAfter(asOf),"timezone",ZONE.toString()),
                "filters",map("item",entity(item,items),"category",entity(category,types),"includeCategoryChildren",category!=null,
                        "warehouse",entity(warehouse,warehouses),"batchCode",batch,"groupBy",group,"stockScope","ALL_PHYSICAL","classificationBasis","CURRENT_ITEM_CATEGORY"),
                "basis","全部实物库存（含线边、隔离、冻结及质量留置）。按当前余额减统计起止时点之后的有符号流水回推；流水时间按上海月份左闭右开统计。内部调拨分别计入入库和出库，净额抵消。导入余额不能代替原始凭据；分类及单位采用当前主数据，不能证明历史分类或单位版本。",
                "complete",complete,"warnings",warnings,"unitGroups",totals,"list",result,"total",result.size(),"pageNo",1,"pageSize",20,"hasMore",result.size()>20,
                "_requiredPermissions",List.of(STOCK_PERMISSION));
    }
    private Amounts postingAmounts(Map<String,Object> t, BigDecimal balance) {
        Amounts a=new Amounts(); a.opening=balance.subtract(decimal(t.get("after_start")));
        a.closing=balance.subtract(decimal(t.get("after_end"))); a.inbound=decimal(t.get("inbound")); a.outbound=decimal(t.get("outbound"));
        if(decimal(t.get("missing_time")).signum()>0) {
            a.unknownBalances("MISSING_TRANSACTION_TIME","流水缺少业务时间，不能可靠分配到月份"); a.inbound=null; a.outbound=null;
        }
        if(decimal(t.get("future_time")).signum()>0) a.unknownBalances("FUTURE_TRANSACTION_TIME","当前库存包含未来时间流水，历史余额待核对");
        if(decimal(t.get("deleted_ledger")).signum()>0) a.unknownBalances("DELETED_TRANSACTION","存在已删除的非零流水，历史余额待核对");
        if(decimal(t.get("missing_quantity")).signum()>0) {
            a.unknownBalances("MISSING_TRANSACTION_QUANTITY","流水数量缺失，不能按零数量处理"); a.inbound=null; a.outbound=null;
        }
        return a;
    }

    private record Header(int type,String table,String permission,String name) { }
    private static final List<Header> HEADERS=List.of(
            new Header(110,"mes_wm_item_receipt","mes:wm-item-receipt:query","采购入库"),
            new Header(111,"mes_wm_transfer","mes:wm-transfer:query","调拨"),
            new Header(113,"mes_wm_misc_issue","mes:wm-misc-issue:query","其他出库"),
            new Header(114,"mes_wm_misc_receipt","mes:wm:misc-receipt:query","其他入库"),
            new Header(115,"mes_wm_product_issue","mes:wm-product-issue:query","生产领料"),
            new Header(116,"mes_wm_return_issue","mes:wm-return-issue:query","生产退料"),
            new Header(117,"mes_wm_product_receipt","mes:wm-product-receipt:query","生产入库"),
            new Header(118,"mes_wm_product_sales","mes:wm-product-sales:query","销售出库"),
            new Header(119,"mes_wm_return_sales","mes:wm-return-sales:query","销售退货"),
            new Header(120,"mes_wm_outsource_issue","mes:wm-outsource-issue:query","外协发料"),
            new Header(121,"mes_wm_outsource_receipt","mes:wm-outsource-receipt:query","外协入库"),
            new Header(124,"mes_wm_return_vendor","mes:wm-return-vendor:query","供应商退货"));
    private static int normalizedType(int type) { return type==112?111:type; }
    private static Header header(int type) { return HEADERS.stream().filter(h->h.type()==normalizedType(type)).findFirst().orElse(null); }
    private boolean canOpen(int type,Long id) { Header h=header(type); return id!=null&&h!=null&&security.hasPermission(h.permission()); }
    private Map<String,Object> source(int type,Long id,Long line) { return map("bizType",type,"bizId",id,"bizLineId",line,"canOpen",canOpen(type,id)); }
    @SuppressWarnings("unchecked")
    public void refreshSourcePermissions(Object value) {
        if(value instanceof Map<?,?> untyped) {
            Map<String,Object> m=(Map<String,Object>)untyped;
            if(m.containsKey("canOpen")&&m.get("bizType") instanceof Number n) m.put("canOpen",canOpen(n.intValue(),id(m.get("bizId"))));
            for(Object child:m.values()) refreshSourcePermissions(child);
        } else if(value instanceof Iterable<?> iterable) for(Object child:iterable) refreshSourcePermissions(child);
    }
    public Map<String,Object> document(Map<String,Object> slots) { return read(() -> documentSnapshot(slots)); }
    private Map<String,Object> documentSnapshot(Map<String,Object> slots) {
        LocalDateTime asOf=now(); String code=text(slots.get("documentCode"));
        if(code==null||code.isBlank()) throw new ClarificationException("NEED_FILTER","请输入准确的库存单据编码",List.of());
        Integer selectedType=slots.get("documentBizType") instanceof Number n?normalizedType(n.intValue()):null;
        Long selectedId=id(slots.get("documentBizId"));
        var identities=jdbc.queryForList("SELECT DISTINCT biz_type,biz_id FROM mes_wm_transaction WHERE tenant_id=? AND deleted=0 AND biz_code=? ORDER BY biz_type,biz_id LIMIT 5001",tenant(),code);
        if(identities.size()>MAX_ROWS) throw narrow();
        Map<String,Map<String,Object>> candidates=new LinkedHashMap<>();
        for(var row:identities) {
            if(!(row.get("biz_type") instanceof Number n)||row.get("biz_id")==null) throw invalid("单据库存流水缺少业务来源标识，请核对原始凭据后再查询");
            int type=normalizedType(n.intValue()); Long bizId=id(row.get("biz_id"));
            if(selectedType!=null&&type!=selectedType || selectedId!=null&&!selectedId.equals(bizId)) continue;
            candidates.putIfAbsent(type+":"+bizId,map("type",type,"id",bizId,"status",null,"known",false));
        }
        Set<String> required=new LinkedHashSet<>(List.of(STOCK_PERMISSION));
        for(Header h:HEADERS) if((selectedType==null||selectedType==h.type())&&security.hasPermission(h.permission())) {
            // Existence/status are private header facts too: record the permission even for a negative result.
            required.add(h.permission());
            String sql="SELECT id,status FROM "+h.table()+" WHERE tenant_id=? AND deleted=0 AND code=?";
            List<Object> args=new ArrayList<>(List.of(tenant(),code));
            if(selectedId!=null) { sql+=" AND id=?"; args.add(selectedId); }
            var headers=jdbc.queryForList(sql+" ORDER BY id LIMIT 5001",args.toArray());
            if(headers.size()>MAX_ROWS) throw new ClarificationException("SCOPE_TOO_LARGE","匹配的单据超过5000张，请缩小单据编码范围",List.of(),new ArrayList<>(required));
            for(var row:headers) {
                Long bizId=id(row.get("id")); var candidate=candidates.computeIfAbsent(h.type()+":"+bizId,k->map("type",h.type(),"id",bizId));
                candidate.put("known",true); candidate.put("status",row.get("status"));
            }
            if(candidates.size()>MAX_ROWS) throw new ClarificationException("SCOPE_TOO_LARGE","匹配的单据超过5000张，请缩小单据编码范围",List.of(),new ArrayList<>(required));
        }
        if(candidates.size()>1) {
            List<Map<String,Object>> choices=new ArrayList<>();
            for(var c:candidates.values()) {
                int type=integer(c.get("type")); Header h=header(type);
                choices.add(map("label",(h==null?"库存业务"+type:h.name())+" "+code+" #"+c.get("id"),
                        "slots",map("documentCode",code,"documentBizType",type,"documentBizId",c.get("id"))));
            }
            throw new ClarificationException("DOCUMENT_AMBIGUOUS","该编码对应多个库存单据，请选择业务来源",choices,new ArrayList<>(required));
        }
        Map<String,Object> candidate=candidates.isEmpty()?map("type",selectedType==null?108:selectedType,"id",selectedId,"known",false,"status",null):candidates.values().iterator().next();
        int type=integer(candidate.get("type")); Long bizId=id(candidate.get("id")); Header h=header(type);
        List<Map<String,Object>> postings;
        if(bizId==null) postings=List.of();
        else {
            String typeSql=type==111?"biz_type IN(111,112)":"biz_type=?";
            List<Object> args=new ArrayList<>(List.of(tenant(),code,bizId)); if(type!=111) args.add(type);
            postings=jdbc.queryForList("SELECT id,transaction_time,biz_type,biz_id,biz_line_id,item_id,batch_id,batch_code,warehouse_id,location_id,area_id,quantity,related_transaction_id FROM mes_wm_transaction WHERE tenant_id=? AND deleted=0 AND biz_code=? AND biz_id=? AND "+typeSql+" ORDER BY transaction_time,id LIMIT 5001",args.toArray());
        }
        if(postings.size()>MAX_ROWS) throw narrow();
        var items=master("mes_md_item",",item_type_id,unit_measure_id",ids(postings,"item_id"));
        var units=master("mes_md_unit_measure","",ids(items.values(),"unit_measure_id"));
        var warehouses=master("mes_wm_warehouse","",ids(postings,"warehouse_id"));
        var locations=master("mes_wm_warehouse_location","",ids(postings,"location_id"));
        var areas=master("mes_wm_warehouse_area","",ids(postings,"area_id"));
        List<Map<String,Object>> rows=new ArrayList<>(),warnings=new ArrayList<>(); Map<String,Map<String,Object>> totals=new LinkedHashMap<>();
        boolean complete=true;
        for(var t:postings) {
            if(t.get("quantity")==null) throw invalid("单据存在缺失数量的库存流水，请核对原始凭据后再查询");
            Long itemId=id(t.get("item_id")); var item=items.get(itemId); Long unitId=item==null?null:id(item.get("unit_measure_id")); var unit=units.get(unitId);
            String unitKey=unit==null?"UNKNOWN_ITEM:"+itemId:"UNIT:"+unitId; BigDecimal q=decimal(t.get("quantity"));
            boolean rowComplete=item!=null&&unit!=null&&time(t.get("transaction_time"))!=null;
            if(time(t.get("transaction_time"))==null) warn(warnings,"MISSING_TRANSACTION_TIME","部分流水缺少时间，无法完整解释业务时点");
            if(time(t.get("transaction_time"))!=null&&time(t.get("transaction_time")).isAfter(asOf)) { rowComplete=false; warn(warnings,"FUTURE_TRANSACTION_TIME","单据含未来时间流水，请核对业务时间"); }
            if(item==null||unit==null) warn(warnings,"MISSING_UNIT_OR_ITEM","部分物料或单位资料缺失，按物料单独列示数量");
            for(String key:List.of("warehouse_id","location_id","area_id")) {
                var masters=key.equals("warehouse_id")?warehouses:key.equals("location_id")?locations:areas;
                if(!masters.containsKey(id(t.get(key)))) { rowComplete=false; warn(warnings,"MISSING_STOCK_POSITION","部分仓库、库区或库位资料缺失"); }
            }
            rows.add(map("id",id(t.get("id")),"transactionAt",iso(time(t.get("transaction_time"))),"bizLineId",id(t.get("biz_line_id")),
                    "item",entity(itemId,items),"batch",map("id",id(t.get("batch_id")),"code",t.get("batch_code")),
                    "warehouse",entity(id(t.get("warehouse_id")),warehouses),"location",entity(id(t.get("location_id")),locations),"area",entity(id(t.get("area_id")),areas),
                    "unitKey",unitKey,"unitName",unit==null?null:unit.get("name"),"quantity",quantity(q),"direction",q.signum()<0?"OUT":"IN", "relatedTransactionId",id(t.get("related_transaction_id")),
                    "source",source(integer(t.get("biz_type")),id(t.get("biz_id")),id(t.get("biz_line_id")))));
            var total=totals.computeIfAbsent(unitKey,k->map("unitKey",unitKey,"unitMeasureId",unit==null?null:unitId,"unitName",unit==null?null:unit.get("name"),
                    "inboundQuantity",BigDecimal.ZERO,"outboundQuantity",BigDecimal.ZERO,"netQuantity",BigDecimal.ZERO,"complete",true));
            total.put(q.signum()<0?"outboundQuantity":"inboundQuantity",decimal(total.get(q.signum()<0?"outboundQuantity":"inboundQuantity")).add(q.abs()));
            total.put("netQuantity",decimal(total.get("netQuantity")).add(q)); total.put("complete",truth(total.get("complete"))&&rowComplete); complete&=rowComplete;
        }
        for(var total:totals.values()) for(String key:List.of("inboundQuantity","outboundQuantity","netQuantity")) total.put(key,quantity(decimal(total.get(key))));
        return map("kind","STOCK_DOCUMENT","asOf",iso(asOf),"document",map("bizType",type,"bizId",bizId,"bizCode",code,"bizTypeName",h==null?"库存业务"+type:h.name(),
                        "postingStatus",postings.isEmpty()?"NO_POSTED_MOVEMENTS":"POSTED","headerKnown",truth(candidate.get("known")),"businessStatus",candidate.get("status"),"source",source(type,bizId,null)),
                "basis","仅按已过账库存有符号流水列示；内部调拨出入分别保留。只有持有来源单据查询权限才核实单头及状态；没有过账流水不代表单据不存在。单位采用当前主数据。",
                "complete",complete,"warnings",warnings,"unitGroups",new ArrayList<>(totals.values()),"list",rows,"total",rows.size(),"pageNo",1,"pageSize",20,"hasMore",rows.size()>20,
                "_requiredPermissions",new ArrayList<>(required));
    }
}
