package cn.iocoder.yudao.module.mes.service.pro.feedback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.feedback.vo.MesProFeedbackFlowVO.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.batch.MesWmBatchMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.invalid;

/** Declared process transfers, frozen atomically with material consumption. Never infer transfers from an order ID. */
@Service
public class MesProFeedbackFlowService {
    @Resource private JdbcTemplate jdbc;
    @Resource private MesWmBatchMapper batchMapper;
    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private long id(Object v) { if (!(v instanceof Number n)) throw invalid("报工来源标识不完整"); return n.longValue(); }
    private BigDecimal n(Object v) { return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()); }
    private boolean flag(Object v) { return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue()!=0; }
    private List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }

    /** Every source/capacity query uses a current locking read after the work-order serialization lock. */
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public Context context(Long taskId, Long feedbackId) {
        State state = state(taskId, feedbackId);
        Map<String,Object> saved = state.feedback;
        Context result = new Context().setFirstProcess(state.previousProcess==null)
                .setRequired(saved==null || !legacy(saved)).setLegacy(saved!=null && legacy(saved))
                .setComplete(saved!=null && !legacy(saved) && flag(saved.get("flow_complete")) && posted(saved));
        Map<Long,Option> options = options(state, feedbackId);
        result.setOptions(new ArrayList<>(options.values()));
        if (feedbackId!=null) for (var row : savedSources(feedbackId)) {
            var source = rows("SELECT id,code,flow_required,flow_complete,status FROM mes_pro_feedback WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", row.get("source_feedback_id"), tenant());
            Allocation allocation = new Allocation();
            allocation.setSourceFeedbackId(id(row.get("source_feedback_id"))).setQuantity(n(row.get("quantity")));
            allocation.setSourceFeedbackCode(source.isEmpty()?"#"+row.get("source_feedback_id"):String.valueOf(source.get(0).get("code")))
                    .setComplete(!source.isEmpty() && flag(source.get(0).get("flow_complete")))
                    .setLegacy(source.isEmpty() || legacy(source.get(0)));
            result.getAllocations().add(allocation);
        }
        return result;
    }

    /** Drafts may be incomplete. A selected source must already be eligible; saving reserves no capacity. */
    @Transactional(rollbackFor=Exception.class)
    public void saveDraft(MesProFeedbackDO feedback, List<Source> inputs) {
        State state = state(feedback.getTaskId(), feedback.getId());
        if (id(state.feedback.get("status"))!=0) throw invalid("只有草稿可以维护报工来源");
        if (savedSources(feedback.getId()).stream().anyMatch(r->flag(r.get("frozen")))) throw invalid("已冻结的报工来源不能修改");
        List<Source> sources = inputs==null?List.of():inputs;
        validate(state, sources, options(state, feedback.getId()), false);
        jdbc.update("DELETE FROM mes_pro_feedback_source WHERE feedback_id=? AND tenant_id=? AND frozen=0", feedback.getId(),tenant());
        for (Source source : sources) jdbc.update("INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity,created_by) VALUES(?,?,?,?,?)",
                tenant(),feedback.getId(),source.getSourceFeedbackId(),source.getQuantity(),String.valueOf(getLoginUserId()));
        jdbc.update("UPDATE mes_pro_feedback SET flow_required=1,flow_complete=0 WHERE id=? AND tenant_id=? AND deleted=0",feedback.getId(),tenant());
    }

    @Transactional(rollbackFor=Exception.class)
    public void deleteDraft(Long feedbackId) {
        // Owning feedback service already holds order -> feedback locks and checks PREPARE.
        if (savedSources(feedbackId).stream().anyMatch(r->flag(r.get("frozen")))) throw invalid("已冻结的报工来源不能删除");
        jdbc.update("DELETE FROM mes_pro_feedback_source WHERE feedback_id=? AND tenant_id=? AND frozen=0",feedbackId,tenant());
    }

    @Transactional(rollbackFor=Exception.class)
    public void validateForSubmission(MesProFeedbackDO feedback) {
        State state = state(feedback.getTaskId(),feedback.getId());
        validate(state, inputs(feedback.getId()), options(state,feedback.getId()), true);
    }

    @Transactional(rollbackFor=Exception.class)
    public void freeze(MesProFeedbackDO feedback) {
        State state = state(feedback.getTaskId(),feedback.getId());
        if (id(state.feedback.get("status"))!=2) throw invalid("只有审批中的报工可以冻结来源");
        List<Source> sources = inputs(feedback.getId());
        Map<Long,Option> options = options(state,feedback.getId());
        validate(state,sources,options,true);
        if (savedSources(feedback.getId()).stream().anyMatch(r->flag(r.get("frozen")))) throw invalid("报工来源已冻结，不能重复处理");
        Set<Long> ancestors = new TreeSet<>();
        boolean complete = true;
        for (Source source : sources) {
            complete &= options.get(source.getSourceFeedbackId()).isComplete();
            ancestors.add(source.getSourceFeedbackId());
            for (var row : rows("SELECT ancestor_feedback_id FROM mes_pro_feedback_ancestor WHERE feedback_id=? AND tenant_id=? AND deleted=0 ORDER BY ancestor_feedback_id FOR UPDATE",source.getSourceFeedbackId(),tenant())) {
                ancestors.add(id(row.get("ancestor_feedback_id")));
            }
        }
        if (ancestors.contains(feedback.getId()) || ancestors.size()>10000) throw invalid("报工来源形成循环或超过一万条安全上限");
        jdbc.update("UPDATE mes_pro_feedback_source SET frozen=1,frozen_at=CURRENT_TIMESTAMP WHERE feedback_id=? AND tenant_id=? AND deleted=0 AND frozen=0",feedback.getId(),tenant());
        for (Long ancestor : ancestors) jdbc.update("INSERT INTO mes_pro_feedback_ancestor(tenant_id,feedback_id,ancestor_feedback_id) VALUES(?,?,?)",tenant(),feedback.getId(),ancestor);
        jdbc.update("UPDATE mes_pro_feedback SET flow_required=1,flow_complete=? WHERE id=? AND tenant_id=? AND deleted=0",complete,feedback.getId(),tenant());
        feedback.setFlowRequired(true).setFlowComplete(complete);
    }

    private List<Map<String,Object>> savedSources(Long feedbackId) {
        return rows("SELECT source_feedback_id,quantity,frozen FROM mes_pro_feedback_source WHERE feedback_id=? AND tenant_id=? AND deleted=0 ORDER BY source_feedback_id FOR UPDATE",feedbackId,tenant());
    }
    private List<Source> inputs(Long feedbackId) {
        return savedSources(feedbackId).stream().map(r->new Source().setSourceFeedbackId(id(r.get("source_feedback_id"))).setQuantity(n(r.get("quantity")))).toList();
    }
    private void validate(State state,List<Source> sources,Map<Long,Option> options,boolean full) {
        if (sources.size()>1000) throw invalid("单次报工最多分配一千条来源");
        if (state.previousProcess==null && !sources.isEmpty()) throw invalid("首工序不能指定前序报工来源");
        Set<Long> seen = new HashSet<>(); BigDecimal total=BigDecimal.ZERO;
        for (Source source : sources) {
            BigDecimal quantity=source==null?null:source.getQuantity();
            if (quantity==null || quantity.signum()<=0 || quantity.stripTrailingZeros().scale()>6
                    || quantity.precision()-quantity.scale()>14 || !seen.add(source.getSourceFeedbackId())) throw invalid("报工来源数量必须为正数、最多六位小数，且来源不能重复");
            Option option=options.get(source.getSourceFeedbackId());
            if (option==null || quantity.compareTo(option.getAvailable())>0) throw invalid("报工来源必须是同工单、同路线的相邻已完成工序，数量不能超过剩余合格量");
            total=total.add(quantity);
        }
        if (full && state.previousProcess!=null && total.compareTo(n(state.feedback.get("feedback_quantity")))!=0) {
            throw invalid("请明确分配前序报工来源，来源数量合计必须等于本次报工数量");
        }
    }
    private Map<Long,Option> options(State state,Long feedbackId) {
        Map<Long,Option> result=new LinkedHashMap<>();
        if (state.previousProcess==null) return result;
        var sources=rows("SELECT id,code,qualified_quantity,flow_required,flow_complete,status FROM mes_pro_feedback WHERE tenant_id=? AND work_order_id=? AND route_id=? AND process_id=? AND item_id=? AND status=4 AND deleted=0 ORDER BY id FOR UPDATE",tenant(),state.orderId,state.routeId,state.previousProcess,state.itemId);
        String processName=String.valueOf(state.previousProcess);
        var names=rows("SELECT name FROM mes_pro_process WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",state.previousProcess,tenant());
        if (!names.isEmpty()) processName=String.valueOf(names.get(0).get("name"));
        for (var source : sources) {
            long sourceId=id(source.get("id")); BigDecimal used=BigDecimal.ZERO;
            // No aggregate consistent read: InnoDB locking reads must see allocations committed while awaiting the order lock.
            for (var allocation : rows("SELECT s.quantity FROM mes_pro_feedback_source s JOIN mes_pro_feedback f ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id AND f.deleted=0 AND f.status IN(3,4) WHERE s.source_feedback_id=? AND s.tenant_id=? AND s.deleted=0 AND s.frozen=1 AND s.feedback_id<>? ORDER BY s.id FOR UPDATE",sourceId,tenant(),feedbackId==null?0:feedbackId)) used=used.add(n(allocation.get("quantity")));
            BigDecimal qualified=n(source.get("qualified_quantity"));
            result.put(sourceId,new Option().setId(sourceId).setCode(String.valueOf(source.get("code"))).setProcessName(processName)
                    .setQualified(qualified).setUsed(used).setAvailable(qualified.subtract(used).max(BigDecimal.ZERO))
                    .setComplete(!legacy(source) && flag(source.get("flow_complete"))).setLegacy(legacy(source)));
        }
        return result;
    }
    private boolean posted(Map<String,Object> feedback) { return id(feedback.get("status"))==3 || id(feedback.get("status"))==4; }
    private boolean legacy(Map<String,Object> feedback) { return posted(feedback) && !flag(feedback.get("flow_required")); }
    private record State(long orderId,long routeId,long itemId,Long previousProcess,Map<String,Object> feedback) {}
    private State state(Long taskId,Long feedbackId) {
        var located=rows("SELECT work_order_id FROM mes_pro_task WHERE id=? AND tenant_id=? AND deleted=0",taskId,tenant());
        if (located.size()!=1) throw invalid("生产任务不存在或无权访问");
        long orderId=id(located.get(0).get("work_order_id"));
        if (rows("SELECT id FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",orderId,tenant()).size()!=1) throw invalid("报工工单不存在或无权访问");
        Map<String,Object> feedback=null;
        if (feedbackId!=null) {
            var reports=rows("SELECT * FROM mes_pro_feedback WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",feedbackId,tenant());
            if (reports.size()!=1 || id(reports.get(0).get("task_id"))!=taskId || id(reports.get(0).get("work_order_id"))!=orderId) throw invalid("报工与任务归属不一致");
            feedback=reports.get(0);
        }
        var tasks=rows("SELECT route_id,process_id,item_id,work_order_id FROM mes_pro_task WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",taskId,tenant());
        if (tasks.size()!=1 || id(tasks.get(0).get("work_order_id"))!=orderId) throw invalid("任务归属已变化，请刷新后重试");
        var task=tasks.get(0); long route=id(task.get("route_id")),process=id(task.get("process_id")),item=id(task.get("item_id"));
        if (feedback!=null && (id(feedback.get("route_id"))!=route || id(feedback.get("process_id"))!=process || id(feedback.get("item_id"))!=item)) throw invalid("报工与任务工艺关系不一致");
        var steps=rows("SELECT process_id,sort FROM mes_pro_route_process WHERE route_id=? AND tenant_id=? AND deleted=0 ORDER BY sort,id FOR UPDATE",route,tenant());
        Set<Long> ids=new HashSet<>(),sorts=new HashSet<>(); int position=-1;
        for (int i=0;i<steps.size();i++) {
            var step=steps.get(i); if (!ids.add(id(step.get("process_id"))) || !sorts.add(id(step.get("sort")))) throw invalid("工艺路线工序及排序必须唯一");
            if (id(step.get("process_id"))==process) position=i;
        }
        if (position<0) throw invalid("报工工序不属于执行路线");
        return new State(orderId,route,item,position==0?null:id(steps.get(position-1).get("process_id")),feedback);
    }

    /** Coverage follows the complete backward batch network, including ancestors with no inventory output. */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Coverage coverage(String code) {
        Coverage coverage=new Coverage();
        coverage.getNotes().add("范围为共享库存批次池的已记录来源关系，不代表单件SN；同批多次报工及分拆合并的用料共同展示。");
        Set<String> visited=new LinkedHashSet<>(); ArrayDeque<Map.Entry<String,Integer>> queue=new ArrayDeque<>();
        if (code==null || code.isBlank()) { coverage.getNotes().add("缺少批次编码"); return coverage; }
        visited.add(code);queue.add(Map.entry(code,0));boolean truncated=false;
        while (!queue.isEmpty()) {
            var step=queue.remove();
            for (var batch : batchMapper.selectListByBackward(step.getKey(),tenant())) {
                if (batch.getCode()==null || batch.getCode().isBlank() || visited.contains(batch.getCode())) continue;
                if (step.getValue()>=20 || visited.size()>=10000) { truncated=true;continue; }
                visited.add(batch.getCode());queue.add(Map.entry(batch.getCode(),step.getValue()+1));
            }
        }
        Set<Long> legacyIds=new TreeSet<>(),inspected=new HashSet<>();boolean complete=true;int reportCount=0;
        for (String batch : visited) {
            var reports=rows("SELECT DISTINCT f.id,f.flow_required,f.flow_complete FROM mes_wm_product_produce_detail d JOIN mes_wm_product_produce p ON p.id=d.produce_id AND p.tenant_id=d.tenant_id AND p.deleted=0 AND p.status=4 JOIN mes_pro_feedback f ON f.id=p.feedback_id AND f.work_order_id=p.work_order_id AND f.item_id=d.item_id AND f.tenant_id=p.tenant_id AND f.deleted=0 AND f.status=4 WHERE d.tenant_id=? AND d.deleted=0 AND d.quantity>0 AND d.batch_code=?",tenant(),batch);
            for (var report : reports) {
                long feedbackId=id(report.get("id"));if (!inspected.add(feedbackId)) continue;reportCount++;
                if (!flag(report.get("flow_required"))) legacyIds.add(feedbackId);
                complete &= flag(report.get("flow_complete"));
                for (var ancestor : rows("SELECT a.ancestor_feedback_id,f.id,f.flow_required,f.flow_complete,f.status,f.deleted FROM mes_pro_feedback_ancestor a LEFT JOIN mes_pro_feedback f ON f.id=a.ancestor_feedback_id AND f.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.feedback_id=? AND a.deleted=0",tenant(),feedbackId)) {
                    if (ancestor.get("id")==null || flag(ancestor.get("deleted")) || id(ancestor.get("status"))!=4 || !flag(ancestor.get("flow_complete"))) complete=false;
                    if (ancestor.get("id")==null || !flag(ancestor.get("flow_required"))) legacyIds.add(id(ancestor.get("ancestor_feedback_id")));
                }
            }
        }
        coverage.setTruncated(truncated).setLegacyFeedbackIds(new ArrayList<>(legacyIds)).setComplete(complete && reportCount>0 && legacyIds.isEmpty() && !truncated);
        if (reportCount==0) coverage.getNotes().add("没有可核验的本批次生产产出证据，不能据此声明来源完整。");
        if (!legacyIds.isEmpty()) coverage.getNotes().add("历史已扣料报工缺少工序流转证据，不按同工单自动补推；已知关系仍可查看。");
        if (!coverage.isComplete() && legacyIds.isEmpty() && reportCount>0) coverage.getNotes().add("存在未完整登记的工序来源链，查询结果仅包含现有证据。");
        if (truncated) coverage.getNotes().add("追溯超过20层或一万批次安全上限，结果已截断。");
        return coverage;
    }
}
