package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.*;
import cn.iocoder.yudao.module.mes.service.report.MesReportTimeFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/** Explicit tenant boundaries, optimistic versions and an immutable transition audit trail. */
@Service
public class MesInsightWorkflow {
    private final JdbcTemplate jdbc;
    public MesInsightWorkflow(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static ServiceException invalid(String message) { return new ServiceException(1_047_160_000,message); }
    public static Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put(values[i].toString(),values[i+1]);
        return result;
    }
    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private long user() {
        Long user=getLoginUserId(); if(user==null) throw invalid("请先登录"); return user;
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args); if(rows.isEmpty()) throw invalid("记录不存在或无权访问"); return rows.get(0);
    }
    public Map<String,Object> order(Long id) {
        return one("SELECT id,code,create_time FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0",id,tenant());
    }
    private void references(Long order,Long station,Long owner) {
        if(order!=null) order(order);
        if(station!=null) one("SELECT id FROM mes_md_workstation WHERE id=? AND tenant_id=? AND deleted=0",station,tenant());
        if(owner!=null) one("SELECT id FROM system_users WHERE id=? AND tenant_id=? AND deleted=0 AND status=0",owner,tenant());
    }
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);return statement;},key);
        if(key.getKey()==null) throw invalid("保存记录失败"); return key.getKey().longValue();
    }
    public Map<String,Object> list() {
        var cases=jdbc.queryForList("SELECT c.id,c.title,c.category,c.work_order_id AS workOrderId,c.workstation_id AS workstationId,c.andon_id AS andonId,c.owner_id AS ownerId,c.due_at AS dueAt,c.description,c.status,c.root_cause AS rootCause,c.corrective_action AS correctiveAction,c.evidence,c.version,c.create_time AS createdAt,c.closed_at AS closedAt,u.nickname AS ownerName,w.code AS orderCode,ws.name AS stationName,assign.department_id AS departmentId,dept.name AS departmentName FROM mes_insight_incident c LEFT JOIN system_users u ON u.id=c.owner_id AND u.tenant_id=c.tenant_id AND u.deleted=0 LEFT JOIN mes_pro_work_order w ON w.id=c.work_order_id AND w.tenant_id=c.tenant_id AND w.deleted=0 LEFT JOIN mes_md_workstation ws ON ws.id=c.workstation_id AND ws.tenant_id=c.tenant_id AND ws.deleted=0 LEFT JOIN mes_insight_incident_assignment assign ON assign.incident_id=c.id AND assign.tenant_id=c.tenant_id LEFT JOIN system_dept dept ON dept.id=assign.department_id AND dept.tenant_id=c.tenant_id AND dept.deleted=0 WHERE c.tenant_id=? AND c.deleted=0 ORDER BY CASE WHEN c.status='CLOSED' THEN 1 ELSE 0 END,c.due_at,c.id DESC LIMIT 501",tenant());
        boolean truncated=cases.size()>500; if(truncated) cases=new ArrayList<>(cases.subList(0,500));
        var andons=jdbc.queryForList("SELECT a.id,a.reason,a.work_order_id AS workOrderId,a.workstation_id AS workstationId,a.level,a.create_time AS createdAt FROM mes_pro_andon_record a WHERE a.tenant_id=? AND a.deleted=0 AND a.status=0 AND NOT EXISTS(SELECT 1 FROM mes_insight_incident c WHERE c.andon_id=a.id AND c.tenant_id=a.tenant_id AND c.deleted=0) ORDER BY a.create_time LIMIT 100",tenant());
        var owners=jdbc.queryForList("SELECT id,nickname AS name FROM system_users WHERE tenant_id=? AND deleted=0 AND status=0 ORDER BY nickname LIMIT 500",tenant());
        var departments=jdbc.queryForList("SELECT id,name FROM system_dept WHERE tenant_id=? AND deleted=0 AND status=0 ORDER BY sort,id LIMIT 254",tenant());
        return MesReportTimeFormat.isoResponse(map("cases",cases,"andons",andons,"owners",owners,"departments",departments,"truncated",truncated));
    }
    private Map<String,Object> incident(long id,boolean lock) {
        return one("SELECT * FROM mes_insight_incident WHERE id=? AND tenant_id=? AND deleted=0"+(lock?" FOR UPDATE":""),id,tenant());
    }
    public Object history(Long id) {
        incident(id,false);
        return MesReportTimeFormat.isoValue(jdbc.queryForList("SELECT e.id,e.from_status AS fromStatus,e.to_status AS toStatus,e.note,e.root_cause AS rootCause,e.corrective_action AS correctiveAction,e.evidence,e.owner_id AS ownerId,e.due_at AS dueAt,e.actor_id AS actorId,e.create_time AS at,u.nickname AS actorName FROM mes_insight_incident_event e LEFT JOIN system_users u ON u.id=e.actor_id AND u.tenant_id=e.tenant_id AND u.deleted=0 WHERE e.incident_id=? AND e.tenant_id=? ORDER BY e.id",id,tenant()));
    }
    private void event(long id,String before,String after,String note,String root,String action,String evidence,Long owner,Object due) {
        jdbc.update("INSERT INTO mes_insight_incident_event(tenant_id,incident_id,from_status,to_status,note,root_cause,corrective_action,evidence,owner_id,due_at,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",tenant(),id,before,after,note,root,action,evidence,owner,due,user());
    }
    @Transactional(rollbackFor=Exception.class)
    public Long create(IncidentCreate req) {
        if(!Set.of("DELIVERY","MATERIAL","QUALITY","DEVICE","PROCESS","OTHER").contains(req.category())) throw invalid("异常分类无效");
        if(req.departmentId()!=null) one("SELECT id FROM system_dept WHERE id=? AND tenant_id=? AND deleted=0 AND status=0",req.departmentId(),tenant());
        if(req.triageId()!=null) {
            var advice=one("SELECT *, (create_time < TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP)) AS expired FROM mes_insight_jev_triage WHERE id=? AND tenant_id=? AND creator=? FOR UPDATE",req.triageId(),tenant(),user());
            if(advice.get("incident_id")!=null) throw invalid("该Jev建议已确认并建立异常，请刷新列表");
            if(!MesJevTriageService.inputHash(req.title(),req.description()).equals(advice.get("input_hash"))) throw invalid("异常内容已改变，请重新分析或清除Jev建议后手动保存");
            Object expired=advice.get("expired");
            if(Boolean.TRUE.equals(expired) || expired instanceof Number value && value.intValue()!=0) throw invalid("Jev建议已过期，请重新分析");
        }
        Long order=req.workOrderId(),station=req.workstationId();
        if(req.andonId()!=null) {
            var andon=one("SELECT work_order_id,workstation_id FROM mes_pro_andon_record WHERE id=? AND tenant_id=? AND deleted=0",req.andonId(),tenant());
            order=andon.get("work_order_id")==null?null:((Number)andon.get("work_order_id")).longValue();
            station=andon.get("workstation_id")==null?null:((Number)andon.get("workstation_id")).longValue();
        }
        references(order,station,req.ownerId());
        try {
            long id=insert("INSERT INTO mes_insight_incident(tenant_id,title,category,work_order_id,workstation_id,andon_id,owner_id,due_at,description,creator,updater) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    tenant(),req.title().strip(),req.category(),order,station,req.andonId(),req.ownerId(),req.dueAt(),req.description(),user(),user());
            if(req.departmentId()!=null || req.triageId()!=null) jdbc.update("INSERT INTO mes_insight_incident_assignment(incident_id,tenant_id,department_id,triage_id) VALUES(?,?,?,?)",id,tenant(),req.departmentId(),req.triageId());
            if(req.triageId()!=null) jdbc.update("UPDATE mes_insight_jev_triage SET incident_id=?,final_category=?,final_department_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND creator=? AND incident_id IS NULL",id,req.category(),req.departmentId(),req.triageId(),tenant(),user());
            event(id,"","OPEN",req.triageId()==null?"创建异常":"创建异常；人工确认Jev建议 #"+req.triageId(),null,null,null,req.ownerId(),req.dueAt()); return id;
        } catch(DuplicateKeyException ex) { throw invalid("该安灯已存在闭环记录，请刷新列表"); }
    }
    private String nonblank(String value) { return value==null?"":value.strip(); }
    @Transactional(rollbackFor=Exception.class)
    public void move(IncidentMove req) {
        var current=incident(req.id(),true);
        if(((Number)current.get("version")).intValue()!=req.version()) throw invalid("记录已被其他用户更新，请刷新后重试");
        String before=current.get("status").toString(),after=req.status();
        boolean allowed=switch(before) {
            case "OPEN" -> after.equals("OPEN") || after.equals("IN_PROGRESS");
            case "IN_PROGRESS" -> after.equals("IN_PROGRESS") || after.equals("VERIFYING");
            case "VERIFYING" -> after.equals("CLOSED") || after.equals("IN_PROGRESS") || after.equals("VERIFYING");
            case "CLOSED" -> after.equals("IN_PROGRESS");
            default -> false;
        };
        if(!allowed) throw invalid("状态流转无效：待处理→处理中→待验证→已关闭；验证失败或复发可回到处理中");
        references(null,null,req.ownerId());
        if(!after.equals("OPEN") && req.ownerId()==null) throw invalid("请先指定负责人");
        if(Set.of("VERIFYING","CLOSED").contains(after) && (nonblank(req.rootCause()).isEmpty() || nonblank(req.correctiveAction()).isEmpty())) throw invalid("进入验证前必须填写确认原因及处理措施");
        if(after.equals("CLOSED") && nonblank(req.evidence()).isEmpty()) throw invalid("关闭异常前必须填写验证通过的证据");
        int changed=jdbc.update("UPDATE mes_insight_incident SET status=?,owner_id=?,due_at=?,root_cause=?,corrective_action=?,evidence=?,version=version+1,updater=?,closed_at=CASE WHEN ?='CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=? AND tenant_id=? AND deleted=0 AND version=?",
                after,req.ownerId(),req.dueAt(),req.rootCause(),req.correctiveAction(),req.evidence(),user(),after,req.id(),tenant(),req.version());
        if(changed!=1) throw invalid("记录已更新，请刷新后重试");
        event(req.id(),before,after,req.note(),req.rootCause(),req.correctiveAction(),req.evidence(),req.ownerId(),req.dueAt());
        if(after.equals("CLOSED") && current.get("andon_id")!=null) {
            jdbc.update("UPDATE mes_pro_andon_record SET status=1,handle_time=CURRENT_TIMESTAMP,handler_user_id=?,remark=?,updater=? WHERE id=? AND tenant_id=? AND deleted=0 AND status=0",
                    user(),"决策中心闭环#"+req.id()+"；验证："+req.evidence(),Long.toString(user()),current.get("andon_id"),tenant());
        } else if(before.equals("CLOSED") && current.get("andon_id")!=null) {
            jdbc.update("UPDATE mes_pro_andon_record SET status=0,handle_time=NULL,handler_user_id=NULL,remark=?,updater=? WHERE id=? AND tenant_id=? AND deleted=0 AND status=1 AND remark LIKE ?",
                    "决策中心闭环#"+req.id()+"；重新处理："+req.note(),Long.toString(user()),current.get("andon_id"),tenant(),"决策中心闭环#"+req.id()+"；%");
        }
    }
    public Object drafts() {
        return MesReportTimeFormat.isoValue(jdbc.queryForList("SELECT id,title,content,work_order_id AS workOrderId,status,incident_id AS incidentId,create_time AS at FROM mes_insight_action_draft WHERE tenant_id=? AND creator=? ORDER BY id DESC LIMIT 100",tenant(),user()));
    }
    @Transactional(rollbackFor=Exception.class)
    public Long saveDraft(DraftCreate req) {
        references(req.workOrderId(),null,null);
        return insert("INSERT INTO mes_insight_action_draft(tenant_id,title,content,work_order_id,creator) VALUES(?,?,?,?,?)",tenant(),req.title().strip(),req.content(),req.workOrderId(),user());
    }
    @Transactional(rollbackFor=Exception.class)
    public Long convertDraft(Long id) {
        var draft=one("SELECT * FROM mes_insight_action_draft WHERE id=? AND tenant_id=? AND creator=? FOR UPDATE",id,tenant(),user());
        if("CONVERTED".equals(draft.get("status"))) return ((Number)draft.get("incident_id")).longValue();
        Long order=draft.get("work_order_id")==null?null:((Number)draft.get("work_order_id")).longValue();
        // create() participates in this transaction; conversion is idempotent under the draft lock.
        long incident=create(new IncidentCreate(draft.get("title").toString(),"OTHER",order,null,null,null,null,draft.get("content").toString()));
        jdbc.update("UPDATE mes_insight_action_draft SET status='CONVERTED',incident_id=? WHERE id=? AND tenant_id=? AND creator=?",incident,id,tenant(),user());
        return incident;
    }
}
