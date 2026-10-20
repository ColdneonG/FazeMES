package cn.iocoder.yudao.module.mes.service.pro.workorder;

import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderLossVO.CloseRequest;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderLossVO.Record;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService.invalid;

/** Operational loss closure. This does not settle scrap/rework costs or manufacture replacement output. */
@Service
public class MesProWorkOrderLossService {
    @Resource private JdbcTemplate jdbc;
    @Resource private MesProTaskService taskService;
    @Resource private MesProKittingService kittingService;
    @Resource @Lazy private MesCostService costService;

    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private BigDecimal n(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private long id(Object value) { return ((Number) value).longValue(); }
    private boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Record preview(Long id) { return inspect(id).record(); }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void close(CloseRequest request) {
        if (request == null || request.getId() == null || request.getExpectedProducedQuantity() == null
                || request.getExpectedProducedQuantity().signum() < 0
                || request.getExpectedProducedQuantity().scale() > 6
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().trim().length() > 500) {
            throw invalid("损耗结案必须提供工单、预览产量及不超过500字的原因");
        }
        Long operator = getLoginUserId();
        if (operator == null) throw invalid("请登录后操作损耗结案");
        Inspection inspection = inspect(request.getId());
        Record record = inspection.record();
        if (record.getProducedQuantity().compareTo(request.getExpectedProducedQuantity()) != 0) {
            throw invalid("报工数据已变化，请重新预览损耗结案");
        }
        costService.assertWorkOrderMutable(request.getId());
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mes_pro_work_order_loss(tenant_id,work_order_id,planned_quantity,produced_quantity,qualified_quantity,loss_quantity,reason,closed_by,closed_at,process_evidence) VALUES(?,?,?,?,?,?,?,?,?,?)",
                tenant(), request.getId(), record.getPlannedQuantity(), record.getProducedQuantity(), record.getQualifiedQuantity(), record.getLossQuantity(),
                request.getReason().trim(), operator, now, JsonUtils.toJsonString(inspection.processEvidence()));
        // These existing operations share this transaction and the same locked work order.
        taskService.finishTaskByOrderId(request.getId());
        kittingService.releaseForClosedOrder(request.getId());
        int changed = jdbc.update("UPDATE mes_pro_work_order SET status=2,finish_date=?,updater=?,update_time=? WHERE id=? AND tenant_id=? AND deleted=0 AND status=1",
                now, String.valueOf(operator), now, request.getId(), tenant());
        if (changed != 1) throw invalid("工单状态已变化，请刷新后重试");
    }

    @Transactional(readOnly = true)
    public Record record(Long workOrderId) {
        if (jdbc.queryForList("SELECT id FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0", workOrderId, tenant()).isEmpty()) {
            throw invalid("工单不存在或无权访问");
        }
        List<Record> records = jdbc.query("SELECT work_order_id,planned_quantity,produced_quantity,qualified_quantity,loss_quantity,reason,closed_by,closed_at FROM mes_pro_work_order_loss WHERE work_order_id=? AND tenant_id=?",
                BeanPropertyRowMapper.newInstance(Record.class), workOrderId, tenant());
        return records.isEmpty() ? null : records.get(0);
    }

    private record Inspection(Record record, List<Map<String, Object>> processEvidence) {}

    /** Work-order -> feedback -> task ordering matches report approval and prevents close/report races. */
    private Inspection inspect(Long workOrderId) {
        var orders = jdbc.queryForList("SELECT quantity,quantity_changed,quantity_produced,status,type FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", workOrderId, tenant());
        if (orders.size() != 1) throw invalid("工单不存在或无权访问");
        var order = orders.get(0);
        if (id(order.get("status")) != 1 || id(order.get("type")) != 1) throw invalid("只有已确认的自产工单可以损耗结案");
        BigDecimal planned = n(order.get("quantity")).add(n(order.get("quantity_changed")));
        if (planned.signum() <= 0) throw invalid("工单计划数量无效，不能损耗结案");
        var feedbacks = jdbc.queryForList("SELECT id,task_id,route_id,process_id,feedback_quantity,qualified_quantity,unqualified_quantity,uncheck_quantity,status FROM mes_pro_feedback WHERE work_order_id=? AND tenant_id=? AND deleted=0 ORDER BY id FOR UPDATE", workOrderId, tenant());
        for (var feedback : feedbacks) {
            if (id(feedback.get("status")) != 4) throw invalid("请先完成或删除全部草稿、审批中及待检报工，再办理损耗结案");
            BigDecimal total = n(feedback.get("feedback_quantity")), good = n(feedback.get("qualified_quantity")), bad = n(feedback.get("unqualified_quantity"));
            if (total.signum() <= 0 || good.signum() < 0 || bad.signum() < 0 || n(feedback.get("uncheck_quantity")).signum() != 0 || good.add(bad).compareTo(total) != 0) {
                throw invalid("存在数量不守恒的历史报工，不能损耗结案");
            }
        }
        var tasks = jdbc.queryForList("SELECT id,route_id,process_id,status FROM mes_pro_task WHERE work_order_id=? AND tenant_id=? AND deleted=0 ORDER BY id FOR UPDATE", workOrderId, tenant());
        var activeTasks = tasks.stream().filter(task -> id(task.get("status")) != 5).toList();
        Set<Long> routes = new HashSet<>();
        activeTasks.forEach(task -> routes.add(id(task.get("route_id"))));
        if (routes.size() != 1) throw invalid("损耗结案需要唯一且完整的执行工艺路线");
        long routeId = routes.iterator().next();
        var processes = jdbc.queryForList("SELECT process_id,sort,key_flag FROM mes_pro_route_process WHERE route_id=? AND tenant_id=? AND deleted=0 ORDER BY sort,id FOR UPDATE", routeId, tenant());
        Set<Long> processIds = new HashSet<>(); Set<Long> sorts = new HashSet<>();
        for (int i = 0; i < processes.size(); i++) {
            var process = processes.get(i);
            if (!processIds.add(id(process.get("process_id"))) || process.get("sort") == null || !sorts.add(id(process.get("sort")))
                    || flag(process.get("key_flag")) != (i == processes.size() - 1)) throw invalid("工艺路线必须唯一排序且仅末道为关键产出工序");
        }
        if (processes.size() < 2) throw invalid("没有可核验的前序损耗，请使用正常完工流程");
        Map<Long, Map<String,Object>> tasksById = new HashMap<>();
        activeTasks.forEach(task -> tasksById.put(id(task.get("id")), task));
        for (var feedback : feedbacks) {
            var task = tasksById.get(id(feedback.get("task_id")));
            if (task == null || id(feedback.get("route_id")) != routeId || !processIds.contains(id(feedback.get("process_id")))
                    || id(task.get("process_id")) != id(feedback.get("process_id"))) throw invalid("报工与任务或工艺归属不一致，不能损耗结案");
        }
        BigDecimal expected = planned, upstreamLoss = BigDecimal.ZERO, produced = BigDecimal.ZERO;
        List<Map<String,Object>> evidence = new ArrayList<>();
        for (int i = 0; i < processes.size(); i++) {
            long processId = id(processes.get(i).get("process_id"));
            BigDecimal total = BigDecimal.ZERO, good = BigDecimal.ZERO, bad = BigDecimal.ZERO;
            for (var feedback : feedbacks) if (id(feedback.get("process_id")) == processId) {
                total = total.add(n(feedback.get("feedback_quantity")));good = good.add(n(feedback.get("qualified_quantity")));bad = bad.add(n(feedback.get("unqualified_quantity")));
            }
            // Full first-stage work and exact downstream processing distinguish real losses from unfinished work.
            if (total.compareTo(expected) != 0) throw invalid("仍有工序未处理完全部前序合格量，不能将未完成作业作为损耗结案");
            evidence.add(Map.of("processId",processId,"reportedQuantity",total,"qualifiedQuantity",good,"unqualifiedQuantity",bad));
            if (i < processes.size() - 1) upstreamLoss = upstreamLoss.add(bad);
            expected = good; produced = total;
        }
        if (produced.compareTo(n(order.get("quantity_produced"))) != 0) throw invalid("末道报工与工单产量不一致，请先核对执行记录");
        if (upstreamLoss.signum() <= 0 || planned.subtract(produced).compareTo(upstreamLoss) != 0) throw invalid("无可核验的前序数量损耗，请使用正常完工流程");
        var wip = jdbc.queryForList("SELECT id FROM mes_wm_material_stock WHERE work_order_id=? AND tenant_id=? AND deleted=0 AND quantity<>0 ORDER BY id FOR UPDATE", workOrderId, tenant());
        if (!wip.isEmpty()) throw invalid("工单线边仍有库存，请先完成入库、退料或合规处置");
        var children = jdbc.queryForList("SELECT id FROM mes_pro_work_order WHERE parent_id=? AND tenant_id=? AND deleted=0 AND status NOT IN(2,3) ORDER BY id FOR UPDATE", workOrderId, tenant());
        if (!children.isEmpty()) throw invalid("仍有未完成的子工单，请先处理后再办理损耗结案");
        Record record = new Record();record.setWorkOrderId(workOrderId);record.setPlannedQuantity(planned);record.setProducedQuantity(produced);record.setQualifiedQuantity(expected);record.setLossQuantity(upstreamLoss);
        return new Inspection(record, evidence);
    }
}
