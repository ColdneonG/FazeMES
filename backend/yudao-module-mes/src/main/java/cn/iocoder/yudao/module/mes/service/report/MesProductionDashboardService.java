package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only business aggregates. Every query and every joined scope is tenant constrained. */
@Service
public class MesProductionDashboardService {
    @Resource private JdbcTemplate jdbc;
    @Resource private MesProKittingService kittingService;
    @Resource private MesDashboardRegionService regionService;
    private record Scope(String sql,List<Object> args) {}
    private Scope scope(String alias,Long workshop,Long station,long tenant) {
        String sql="";List<Object> args=new ArrayList<>();
        if(station!=null){sql+=" AND "+alias+".workstation_id=?";args.add(station);}
        if(workshop!=null){sql+=" AND EXISTS(SELECT 1 FROM mes_md_workstation ws WHERE ws.id="+alias+".workstation_id AND ws.tenant_id=? AND ws.deleted=0 AND ws.workshop_id=?)";args.add(tenant);args.add(workshop);}
        return new Scope(sql,args);
    }
    private List<Map<String,Object>> rows(String sql,List<Object> args) {
        return jdbc.query(sql,(r,n)->{var md=r.getMetaData();Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++){Object v=r.getObject(i);row.put(md.getColumnLabel(i),v instanceof java.sql.Timestamp ts?ts.toLocalDateTime():v);}return row;},args.toArray());
    }
    private List<Object> args(Object...values){return new ArrayList<>(Arrays.asList(values));}
    /** Scoped date hints never silently change the selected day or include future activity. */
    Map<String,Object> availability(Long workshop,Long station,LocalDate today) {
        long tenant=TenantContextHolder.getRequiredTenantId();
        var f=scope("f",workshop,station,tenant);
        var t=scope("t",workshop,station,tenant);
        List<Object> values=args(tenant,today.plusDays(1).atStartOfDay());values.addAll(f.args());
        values.addAll(args(tenant,today.plusDays(1).atStartOfDay()));values.addAll(t.args());
        return rows("SELECT MAX(activity_day) AS latestActivityDate FROM ("
                +"SELECT MAX(DATE(f.feedback_time)) AS activity_day FROM mes_pro_feedback f WHERE f.tenant_id=? AND f.deleted=0 AND f.status=4 AND f.feedback_time<?"+f.sql()
                +" UNION ALL SELECT MAX(DATE(t.start_time)) AS activity_day FROM mes_pro_task t WHERE t.tenant_id=? AND t.deleted=0 AND t.status<>5 AND t.start_time<?"+t.sql()
                +") activity",values).get(0);
    }
    public Map<String,Object> production(Long workshop,Long station,LocalDate date) {
        if(date==null)date=LocalDate.now();long tenant=TenantContextHolder.getRequiredTenantId();
        LocalDateTime from=date.atStartOfDay(),until=date.plusDays(1).atStartOfDay();var s=scope("f",workshop,station,tenant);
        List<Object> feedbackArgs=args(tenant,from,until);feedbackArgs.addAll(s.args());
        var production=rows("SELECT COALESCE(SUM(f.feedback_quantity),0) AS produced,COALESCE(SUM(f.qualified_quantity),0) AS qualified,COALESCE(SUM(f.unqualified_quantity),0) AS rejected,COUNT(*) AS feedbackCount FROM mes_pro_feedback f WHERE f.tenant_id=? AND f.deleted=0 AND f.status=4 AND f.feedback_time>=? AND f.feedback_time<?"+s.sql(),feedbackArgs).get(0);
        var t=scope("t",workshop,station,tenant);List<Object> taskArgs=args(tenant,until,from);taskArgs.addAll(t.args());
        String taskWhere=" FROM mes_pro_task t WHERE t.tenant_id=? AND t.deleted=0 AND t.status<>5 AND t.start_time<? AND COALESCE(t.end_time,t.start_time)>=?"+t.sql();
        var target=rows("SELECT COALESCE(SUM(t.quantity),0) AS target,COUNT(*) AS taskCount"+taskWhere,taskArgs).get(0);
        var tasks=rows("SELECT t.id,t.code,t.name,t.work_order_id AS workOrderId,t.workstation_id AS workstationId,t.quantity,t.produced_quantity AS producedQuantity,t.status,t.start_time AS startTime,t.end_time AS endTime"+taskWhere+" ORDER BY t.start_time,t.id LIMIT 100",taskArgs);
        var q=scope("q",workshop,station,tenant);List<Object> qualityArgs=args(tenant,until);qualityArgs.addAll(q.args());
        var pending=rows("SELECT COUNT(*) AS pendingInspection FROM mes_qc_ipqc q WHERE q.tenant_id=? AND q.deleted=0 AND q.status=0 AND q.create_time<?"+q.sql(),qualityArgs).get(0);
        var a=scope("a",workshop,station,tenant);List<Object> andonArgs=args(tenant,until);andonArgs.addAll(a.args());
        var andon=rows("SELECT COUNT(*) AS activeAndon FROM mes_pro_andon_record a WHERE a.tenant_id=? AND a.deleted=0 AND a.status=0 AND a.create_time<?"+a.sql(),andonArgs).get(0);
        List<Object> trendArgs=args(tenant,date.minusDays(6).atStartOfDay(),until);trendArgs.addAll(s.args());
        var trend=rows("SELECT DATE(f.feedback_time) AS day,COALESCE(SUM(f.feedback_quantity),0) AS produced,COALESCE(SUM(f.qualified_quantity),0) AS qualified FROM mes_pro_feedback f WHERE f.tenant_id=? AND f.deleted=0 AND f.status=4 AND f.feedback_time>=? AND f.feedback_time<?"+s.sql()+" GROUP BY DATE(f.feedback_time) ORDER BY day",trendArgs);
        List<Object> deviceArgs=args(tenant);String deviceScope="";
        if(workshop!=null){deviceScope+=" AND d.workshop_id=?";deviceArgs.add(workshop);}
        if(station!=null){deviceScope+=" AND EXISTS(SELECT 1 FROM mes_md_workstation_machine m WHERE m.machinery_id=d.id AND m.workstation_id=? AND m.tenant_id=? AND m.deleted=0)";deviceArgs.add(station);deviceArgs.add(tenant);}
        var devices=rows("SELECT d.status,COUNT(*) AS count FROM mes_dv_machinery d WHERE d.tenant_id=? AND d.deleted=0"+deviceScope+" GROUP BY d.status",deviceArgs);
        List<Object> workArgs=args(tenant);workArgs.addAll(taskArgs);
        var workOrders=rows("SELECT w.id,w.code,w.name,w.quantity,w.quantity_produced AS producedQuantity,w.status FROM mes_pro_work_order w WHERE w.tenant_id=? AND w.deleted=0 AND EXISTS(SELECT 1"+taskWhere+" AND t.work_order_id=w.id) ORDER BY w.request_date,w.id LIMIT 30",workArgs);
        List<Object> stationArgs=args(tenant);String stationWhere="";if(workshop!=null){stationWhere+=" AND workshop_id=?";stationArgs.add(workshop);}
        var stations=rows("SELECT id,name,workshop_id AS workshopId FROM mes_md_workstation WHERE tenant_id=? AND deleted=0"+stationWhere+" ORDER BY name LIMIT 500",stationArgs);
        var workshops=rows("SELECT id,name FROM mes_md_workshop WHERE tenant_id=? AND deleted=0 ORDER BY name LIMIT 500",args(tenant));
        Map<String,Object> result=new LinkedHashMap<>();result.putAll(production);result.putAll(target);result.putAll(pending);result.putAll(andon);
        result.put("tasks",tasks);result.put("workOrders",workOrders);result.put("trend",trend);result.put("devices",devices);result.put("workstations",stations);result.put("workshops",workshops);
        result.put("date",date);result.put("updatedAt",LocalDateTime.now());
        result.put("availability",availability(workshop,station,LocalDate.now()));
        result.put("regionalDistribution",regionService.distribution(workshop,station,date));
        result.put("shortageAvailable",true);result.put("shortageWorkOrderCount",kittingService.board(null,true).size());
        result.put("basis","产量与合格率：所选日期已完成报工（feedback_time，状态4）；目标：当日交叠工序任务数量；待检/安灯：该日结束前创建且当前未关闭记录；设备：当前台账状态。缺料：全厂当前未结工单齐套分析，与日期、车间、工位筛选无关。数量跨产品时不统一计量单位。");
        return MesReportTimeFormat.isoResponse(result);
    }
}
