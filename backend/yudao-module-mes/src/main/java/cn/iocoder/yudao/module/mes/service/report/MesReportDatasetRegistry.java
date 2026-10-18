package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportConfig;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.report.MesReportErrorConstants.invalid;

/** Every SQL identifier originates in this server-owned registry. User values use JDBC parameters. */
@Component
public class MesReportDatasetRegistry {
    public record Field(String key, String title, String type) {}
    public record Dataset(String key, String name, String table, List<Field> fields) {}
    public record Query(String sql, List<Object> parameters, List<Map<String,Object>> columns) {}
    private final Map<String, Dataset> datasets = new LinkedHashMap<>();
    public MesReportDatasetRegistry() {
        add("workorders", "生产工单", "mes_pro_work_order", "id:编号:n,code:工单编码:s,name:工单名称:s,product_id:产品ID:n,quantity:计划数量:n,quantity_produced:已产数量:n,status:状态:n,request_date:需求日期:d,create_time:创建时间:d");
        add("feedback", "生产报工", "mes_pro_feedback", "id:编号:n,code:报工编码:s,work_order_id:工单ID:n,task_id:任务ID:n,workstation_id:工位ID:n,feedback_time:报工时间:d,feedback_quantity:报工数量:n,qualified_quantity:合格数量:n,unqualified_quantity:不合格数量:n,uncheck_quantity:待检数量:n,status:状态:n");
        add("inventory", "库存台账", "mes_wm_material_stock", "id:编号:n,item_id:物料ID:n,batch_code:批次:s,warehouse_id:仓库ID:n,location_id:库区ID:n,area_id:库位ID:n,quantity:库存数量:n,frozen:冻结状态:n,receipt_time:入库时间:d");
        add("quality", "过程质检 IPQC", "mes_qc_ipqc", "id:编号:n,code:检验编码:s,name:检验名称:s,type:检验类型:n,work_order_id:工单ID:n,workstation_id:工位ID:n,check_quantity:检验数量:n,qualified_quantity:合格数量:n,unqualified_quantity:不合格数量:n,status:状态:n,check_result:检验结果:n,inspect_date:检验日期:d");
        add("equipmentTasks", "设备点检保养任务", "mes_dv_task", "id:编号:n,plan_name:方案名称:s,machinery_id:设备ID:n,machinery_name:设备名称:s,type:任务类型:n,status:状态:n,due_time:到期时间:d,completed_time:完成时间:d");
    }
    private void add(String key,String name,String table,String fields) {
        datasets.put(key,new Dataset(key,name,table,Arrays.stream(fields.split(",")).map(x->{String[] p=x.split(":");return new Field(p[0],p[1],p[2]);}).toList()));
    }
    public List<Dataset> list() {return new ArrayList<>(datasets.values());}
    public Query compile(MesReportConfig c, long tenant, int page, int size) {
        if (c == null || !datasets.containsKey(c.getDataset())) throw invalid("请选择有效业务数据集");
        if (c.getChart()==null || !Set.of("table","bar","line","pie").contains(c.getChart())) throw invalid("不支持的图表类型");
        if (c.getColumns()==null || c.getColumns().isEmpty() || c.getColumns().size()>30) throw invalid("请选择1至30个列");
        if (c.getFilters()==null || c.getFilters().size()>20 || c.getGroupBy()==null || c.getGroupBy().size()>5) throw invalid("筛选或分组超限");
        Dataset d=datasets.get(c.getDataset());
        Map<String,Field> fields=new LinkedHashMap<>();d.fields().forEach(f->fields.put(f.key(),f));
        if(c.getColumns().contains(null)||c.getFilters().contains(null))throw invalid("列或筛选不能为空");
        Set<String> groups=new LinkedHashSet<>(c.getGroupBy());
        groups.forEach(f->requireField(fields,f));
        boolean grouped=!groups.isEmpty() || c.getColumns().stream().anyMatch(x->x.getAggregate()!=null&&!x.getAggregate().isBlank());
        List<String> selects=new ArrayList<>();List<Map<String,Object>> columns=new ArrayList<>();Set<String> seen=new HashSet<>();
        for (MesReportConfig.Column col:c.getColumns()) {
            requireField(fields,col.getField());
            if(!seen.add(col.getField())) throw invalid("字段不能重复");
            if(col.getTitle()==null||col.getTitle().isBlank()||col.getTitle().length()>100) throw invalid("列标题需为1至100个字符");
            if(col.getWidth()==null||col.getWidth()<60||col.getWidth()>600) throw invalid("列宽必须在60至600之间");
            String agg=col.getAggregate();String expression="t."+col.getField();
            if(agg!=null&&!agg.isBlank()) {
                if(!Set.of("SUM","AVG","MIN","MAX","COUNT").contains(agg)) throw invalid("不支持的聚合函数");
                if(!"COUNT".equals(agg)&&!"n".equals(fields.get(col.getField()).type())) throw invalid("聚合仅支持数值字段");
                expression=agg+"("+expression+")";
            } else if(grouped&&!groups.contains(col.getField())) throw invalid("非聚合列必须加入分组");
            if(!Boolean.FALSE.equals(col.getVisible())) {
                String alias="c"+columns.size();selects.add(expression+" AS "+alias);
                columns.add(Map.of("key",alias,"field",col.getField(),"title",col.getTitle(),"width",col.getWidth(),"type",(agg!=null&&!agg.isBlank())?"n":fields.get(col.getField()).type()));
            }
        }
        if(selects.isEmpty()) throw invalid("至少显示一列");
        MesReportCanvasValidator.validate(c,columns);
        StringBuilder sql=new StringBuilder("SELECT "+String.join(",",selects)+" FROM "+d.table()+" t WHERE t.tenant_id=? AND t.deleted=0");
        List<Object> args=new ArrayList<>();args.add(tenant);
        for(MesReportConfig.Filter f:c.getFilters()) {
            requireField(fields,f.getField());
            if(f.getValue()==null||f.getValue().length()>500) throw invalid("筛选值超限");
            String op=switch(f.getOperator()==null?"":f.getOperator()) {case "eq"->"=";case "ne"->"<>";case "gt"->">";case "ge"->">=";case "lt"->"<";case "le"->"<=";case "contains"->"LIKE";default->throw invalid("无效筛选操作");};
            Object value;
            try {value=switch(fields.get(f.getField()).type()) {case "n"->new BigDecimal(f.getValue());case "d"->LocalDateTime.parse(f.getValue().replace(' ','T'));default->f.getValue();};}
            catch(RuntimeException ex){throw invalid("筛选值类型不正确："+fields.get(f.getField()).title());}
            if("LIKE".equals(op)) {if(!"s".equals(fields.get(f.getField()).type())) throw invalid("包含仅支持文本字段");value="%"+f.getValue().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
            sql.append(" AND t.").append(f.getField()).append(" ").append(op).append(" ?").append("LIKE".equals(op)?" ESCAPE '!'":"");args.add(value);
        }
        if(!groups.isEmpty()) sql.append(" GROUP BY ").append(String.join(",",groups.stream().map(x->"t."+x).toList()));
        if(c.getSortField()!=null&&!c.getSortField().isBlank()) {
            int index=-1;for(int i=0;i<columns.size();i++) if(c.getSortField().equals(columns.get(i).get("field")))index=i;
            if(index<0) throw invalid("排序字段必须是显示列");
            if(c.getSortDirection()==null||!Set.of("ASC","DESC").contains(c.getSortDirection())) throw invalid("无效排序方向");
            sql.append(" ORDER BY c").append(index).append(' ').append(c.getSortDirection());
        } else sql.append(" ORDER BY c0 ASC");
        if(page<1||page>10000||size<1||size>5000) throw invalid("分页超出允许范围");
        sql.append(" LIMIT ? OFFSET ?");args.add(size+1);args.add((page-1)*size);
        return new Query(sql.toString(),args,columns);
    }
    private void requireField(Map<String,Field> fields,String field) {if(!fields.containsKey(field)) throw invalid("字段不在业务白名单中");}
}
