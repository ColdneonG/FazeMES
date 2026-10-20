package cn.iocoder.yudao.module.mes.service.pro.integrity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** Native production invariants. Call mutations inside the owning business transaction. */
@Service
public class MesProductionIntegrityService {
    @Resource private JdbcTemplate jdbc;

    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    public static ServiceException invalid(String message) { return new ServiceException(1_047_160_000, message); }
    private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private BigDecimal currentSum(String sql, Object... arguments) {
        return jdbc.queryForList(sql, BigDecimal.class, arguments).stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A per-tenant mutex makes opposite concurrent BOM edits observe each other's committed edges. */
    public void lockBomGraph() {
        long tenant = tenant();
        jdbc.update("INSERT IGNORE INTO mes_pro_integrity_lock(tenant_id,scope) VALUES(?,'BOM')", tenant);
        jdbc.queryForObject("SELECT tenant_id FROM mes_pro_integrity_lock WHERE tenant_id=? AND scope='BOM' FOR UPDATE", Long.class, tenant);
    }

    public void validateBomEditable(Long itemId) {
        var routes = jdbc.queryForList("""
                SELECT r.id FROM mes_pro_route_product p
                JOIN mes_pro_route r ON r.id=p.route_id AND r.tenant_id=p.tenant_id AND r.deleted=0
                WHERE p.tenant_id=? AND p.deleted=0 AND p.item_id=? AND r.status=0 FOR UPDATE
                """, tenant(), itemId);
        if (!routes.isEmpty()) throw invalid("产品已有启用的工艺路线，请先停用路线再修改BOM，并重新核对工序用料");
        var orders = jdbc.queryForList("SELECT id FROM mes_pro_work_order WHERE product_id=? AND tenant_id=? AND deleted=0 AND status=1 FOR UPDATE", itemId, tenant());
        if (!orders.isEmpty()) throw invalid("产品仍有已确认工单，不能修改其BOM，请通过新产品版本生产");
    }

    /** Scheduling and configuration edits share the graph mutex; used routes remain historical versions. */
    public void validateRouteEditable(Long routeId) {
        var routes = jdbc.queryForList("SELECT status FROM mes_pro_route WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", routeId, tenant());
        if (routes.isEmpty()) throw invalid("工艺路线不存在或无权访问");
        if (((Number) routes.get(0).get("status")).intValue() == 0) throw invalid("请先停用工艺路线，再修改其配置");
        var tasks = jdbc.queryForList("SELECT id FROM mes_pro_task WHERE route_id=? AND tenant_id=? AND deleted=0 FOR UPDATE", routeId, tenant());
        if (!tasks.isEmpty()) throw invalid("工艺路线已被生产任务使用，请建立新版本，保留历史工艺配置");
    }

    /** Activation verifies equality, not merely that some consumption row exists. */
    public void validateRoute(Long routeId) {
        long tenant = tenant();
        var processes = jdbc.queryForList("SELECT process_id,sort,key_flag,check_flag FROM mes_pro_route_process WHERE route_id=? AND tenant_id=? AND deleted=0 ORDER BY sort,id", routeId, tenant);
        if (processes.isEmpty()) throw invalid("工艺路线必须配置工序");
        int keys = 0;
        Set<Long> processIds = new HashSet<>();
        Set<Integer> sorts = new HashSet<>();
        for (int i = 0; i < processes.size(); i++) {
            var process = processes.get(i);
            if (!processIds.add(((Number) process.get("process_id")).longValue())
                    || process.get("sort") == null || !sorts.add(((Number) process.get("sort")).intValue())) {
                throw invalid("工艺路线不能包含重复工序或重复排序");
            }
            boolean key = flag(process.get("key_flag"));
            if (flag(process.get("check_flag")) && !key) throw invalid("非关键工序请使用SOP记录检查，独立检验标识仅用于末道关键工序");
            if (key) { keys++; if (i != processes.size() - 1) throw invalid("产生产品库存的关键工序必须是最后一道工序"); }
        }
        if (keys != 1) throw invalid("每条工艺路线必须且只能有一个末道关键工序");
        var products = jdbc.queryForList("SELECT item_id FROM mes_pro_route_product WHERE route_id=? AND tenant_id=? AND deleted=0", routeId, tenant);
        if (products.isEmpty()) throw invalid("工艺路线必须关联产品");
        for (var product : products) {
            long itemId = ((Number) product.get("item_id")).longValue();
            var bom = jdbc.queryForList("SELECT bom_item_id,quantity FROM mes_md_product_bom WHERE item_id=? AND status=0 AND tenant_id=? AND deleted=0", itemId, tenant);
            Map<Long, BigDecimal> expected = new HashMap<>();
            for (var row : bom) {
                long child = ((Number) row.get("bom_item_id")).longValue();
                BigDecimal quantity = decimal(row.get("quantity"));
                if (quantity.signum() <= 0 || expected.put(child, quantity) != null) throw invalid("产品BOM含非正用量或重复物料，请先修正");
            }
            if (expected.isEmpty()) throw invalid("产品未配置有效BOM");
            Map<Long, BigDecimal> allocated = new HashMap<>();
            var consumption = jdbc.queryForList("SELECT process_id,item_id,quantity FROM mes_pro_route_product_bom WHERE route_id=? AND product_id=? AND tenant_id=? AND deleted=0", routeId, itemId, tenant);
            for (var row : consumption) {
                long child = ((Number) row.get("item_id")).longValue();
                if (!processIds.contains(((Number) row.get("process_id")).longValue()) || !expected.containsKey(child)) throw invalid("工序用料必须属于本路线工序及产品本层BOM");
                BigDecimal quantity = decimal(row.get("quantity"));
                if (quantity.signum() <= 0) throw invalid("工序用料必须为正数");
                allocated.merge(child, quantity, BigDecimal::add);
            }
            for (var row : expected.entrySet()) {
                if (row.getValue().compareTo(allocated.getOrDefault(row.getKey(), BigDecimal.ZERO)) != 0) throw invalid("工序分配用量合计必须等于产品BOM用量，物料ID：" + row.getKey());
            }
        }
    }

    private boolean flag(Object value) { return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() != 0); }

    /** Parent row is the serialization point for concurrent child creation and quantity changes. */
    public void validateChildWorkOrder(MesProWorkOrderSaveReqVO request, Long excludingId) {
        if (request.getQuantity() == null || request.getQuantity().signum() <= 0) throw invalid("工单数量必须大于零");
        Long parentId = request.getParentId();
        if (parentId == null || parentId == 0) return;
        if (parentId.equals(excludingId)) throw invalid("工单不能以自身为父工单");
        long tenant = tenant();
        var parents = jdbc.queryForList("SELECT id,status,type,product_id FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", parentId, tenant);
        if (parents.size() != 1) throw invalid("父工单不存在或不属于当前租户");
        var parent = parents.get(0);
        if (((Number) parent.get("status")).intValue() != 1 || ((Number) parent.get("type")).intValue() != 1) throw invalid("只能为已确认的自产工单生成半成品子工单");
        if (Objects.equals(((Number) parent.get("product_id")).longValue(), request.getProductId())) throw invalid("父子工单不能生产同一产品");
        Long products = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mes_md_item i JOIN mes_md_item_type t ON t.id=i.item_type_id AND t.tenant_id=i.tenant_id AND t.deleted=0
                WHERE i.id=? AND i.tenant_id=? AND i.deleted=0 AND t.item_or_product='PRODUCT'
                """, Long.class, request.getProductId(), tenant);
        if (products == null || products == 0) throw invalid("子工单产品必须是可生产的产品或半成品");
        BigDecimal required = currentSum("SELECT quantity FROM mes_pro_work_order_bom WHERE work_order_id=? AND item_id=? AND tenant_id=? AND deleted=0 FOR UPDATE", parentId, request.getProductId(), tenant);
        if (required == null || required.signum() <= 0) throw invalid("子工单产品必须属于父工单本层BOM");
        // Loss-closed children supply verified final good units; their original plan stays immutable.
        // Unfinished children continue reserving the complete planned quantity.
        BigDecimal allocated = currentSum("""
                SELECT CASE WHEN w.status=2 AND loss.work_order_id IS NOT NULL THEN loss.qualified_quantity ELSE w.quantity END
                FROM mes_pro_work_order w
                LEFT JOIN mes_pro_work_order_loss loss ON loss.work_order_id=w.id AND loss.tenant_id=w.tenant_id
                WHERE w.parent_id=? AND w.product_id=? AND w.tenant_id=? AND w.deleted=0 AND w.status<>3 AND w.id<>? FOR UPDATE
                """, parentId, request.getProductId(), tenant, excludingId == null ? 0 : excludingId);
        if (decimal(allocated).add(request.getQuantity()).compareTo(required) > 0) throw invalid("子工单累计数量超过父工单BOM需求，不能重复生成");
    }

    public void validateParentChange(Long id) {
        var children = jdbc.queryForList("SELECT id FROM mes_pro_work_order WHERE parent_id=? AND tenant_id=? AND deleted=0 AND status<>3 FOR UPDATE", id, tenant());
        if (!children.isEmpty()) throw invalid("工单已有有效子工单，不能再修改产品或数量");
    }

    /** Execution consumes route quotas, so an order cannot silently use a different recipe. */
    public void validateWorkOrderRecipe(Long id) {
        var orders = jdbc.queryForList("SELECT product_id,quantity,type FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", id, tenant());
        if (orders.size() != 1) throw invalid("工单不存在或无权访问");
        var order = orders.get(0);
        if (((Number) order.get("type")).intValue() != 1) return;
        var bom = jdbc.queryForList("SELECT bom_item_id,quantity FROM mes_md_product_bom WHERE item_id=? AND tenant_id=? AND deleted=0 AND status=0 FOR UPDATE", order.get("product_id"), tenant());
        Map<Long, BigDecimal> expected = new HashMap<>();
        for (var row : bom) {
            Long child = ((Number) row.get("bom_item_id")).longValue();
            BigDecimal quantity = decimal(row.get("quantity")).multiply(decimal(order.get("quantity")));
            if (quantity.signum() <= 0 || expected.put(child, quantity) != null) throw invalid("产品BOM用量无效或含重复物料");
        }
        if (expected.isEmpty()) throw invalid("自产工单必须配置有效的本层BOM");
        var lines = jdbc.queryForList("SELECT item_id,quantity FROM mes_pro_work_order_bom WHERE work_order_id=? AND tenant_id=? AND deleted=0 FOR UPDATE", id, tenant());
        Set<Long> actual = new HashSet<>();
        for (var line : lines) {
            Long child = ((Number) line.get("item_id")).longValue();
            if (!actual.add(child) || !expected.containsKey(child) || decimal(line.get("quantity")).compareTo(expected.get(child)) != 0) {
                throw invalid("工单BOM必须与本层产品BOM及工单数量一致；变更配方请建立产品和工艺新版本");
            }
        }
        if (actual.size() != expected.size()) throw invalid("工单BOM缺少本层产品物料，请重新生成并核对");
    }

    /** Caller already holds work-order -> feedback -> task locks. Pending QC reserves reported capacity. */
    public void validateFeedbackSequence(MesProFeedbackDO feedback) {
        long tenant = tenant();
        var orders = jdbc.queryForList("SELECT quantity,quantity_changed FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", feedback.getWorkOrderId(), tenant);
        if (orders.size() != 1) throw invalid("报工工单不存在或无权访问");
        BigDecimal orderCapacity = decimal(orders.get(0).get("quantity")).add(decimal(orders.get(0).get("quantity_changed")));
        BigDecimal processSubmitted = currentSum("SELECT feedback_quantity FROM mes_pro_feedback WHERE work_order_id=? AND route_id=? AND process_id=? AND tenant_id=? AND deleted=0 AND status IN(3,4) AND id<>? FOR UPDATE", feedback.getWorkOrderId(), feedback.getRouteId(), feedback.getProcessId(), tenant, feedback.getId());
        if (processSubmitted.add(feedback.getFeedbackQuantity()).compareTo(orderCapacity) > 0) throw invalid("本工序全部任务累计报工量（含待检）超过工单数量");
        var tasks = jdbc.queryForList("SELECT quantity,changed_quantity FROM mes_pro_task WHERE id=? AND work_order_id=? AND tenant_id=? AND deleted=0 FOR UPDATE", feedback.getTaskId(), feedback.getWorkOrderId(), tenant);
        if (tasks.size() != 1) throw invalid("报工任务不存在或工单归属已变化");
        BigDecimal submitted = currentSum("SELECT feedback_quantity FROM mes_pro_feedback WHERE task_id=? AND work_order_id=? AND tenant_id=? AND deleted=0 AND status IN(3,4) AND id<>? FOR UPDATE", feedback.getTaskId(), feedback.getWorkOrderId(), tenant, feedback.getId());
        BigDecimal capacity = decimal(tasks.get(0).get("quantity")).add(decimal(tasks.get(0).get("changed_quantity")));
        if (decimal(submitted).add(feedback.getFeedbackQuantity()).compareTo(capacity) > 0) throw invalid("累计报工数量（含待检）超过任务数量");
        var processes = jdbc.queryForList("SELECT process_id,link_type FROM mes_pro_route_process WHERE route_id=? AND tenant_id=? AND deleted=0 ORDER BY sort,id FOR UPDATE", feedback.getRouteId(), tenant);
        int position = -1;
        for (int i = 0; i < processes.size(); i++) if (((Number) processes.get(i).get("process_id")).longValue() == feedback.getProcessId()) position = i;
        if (position < 0) throw invalid("报工工序已不属于当前路线");
        if (position == 0) return;
        var previous = processes.get(position - 1);
        // Other link types retain their explicitly configured overlapping semantics.
        if (previous.get("link_type") == null || ((Number) previous.get("link_type")).intValue() != 3) return;
        long previousId = ((Number) previous.get("process_id")).longValue();
        BigDecimal released = currentSum("SELECT qualified_quantity FROM mes_pro_feedback WHERE work_order_id=? AND route_id=? AND process_id=? AND tenant_id=? AND deleted=0 AND status=4 FOR UPDATE", feedback.getWorkOrderId(), feedback.getRouteId(), previousId, tenant);
        BigDecimal downstream = currentSum("SELECT feedback_quantity FROM mes_pro_feedback WHERE work_order_id=? AND route_id=? AND process_id=? AND tenant_id=? AND deleted=0 AND status IN(3,4) AND id<>? FOR UPDATE", feedback.getWorkOrderId(), feedback.getRouteId(), feedback.getProcessId(), tenant, feedback.getId());
        if (decimal(downstream).add(feedback.getFeedbackQuantity()).compareTo(decimal(released)) > 0) throw invalid("本工序累计报工量超过前序已完成合格量，请先完成前序作业");
    }

    public void validateWorkOrderFinish(Long id) {
        long tenant = tenant();
        var pending = jdbc.queryForList("SELECT id FROM mes_pro_feedback WHERE work_order_id=? AND tenant_id=? AND deleted=0 AND status IN(2,3) FOR UPDATE", id, tenant);
        if (!pending.isEmpty()) throw invalid("工单仍有审批中或待检报工，不能完工");
        var incomplete = jdbc.queryForList("SELECT id FROM mes_pro_work_order WHERE id=? AND tenant_id=? AND deleted=0 AND COALESCE(quantity_produced,0)<quantity+COALESCE(quantity_changed,0) FOR UPDATE", id, tenant);
        if (!incomplete.isEmpty()) throw invalid("实际产出未达到工单数量，不能完工");
        validateEmptyWip(id);
    }

    public void validateWorkOrderCancel(Long id) {
        validateParentChange(id);
        var executed = jdbc.queryForList("SELECT id FROM mes_pro_feedback WHERE work_order_id=? AND tenant_id=? AND deleted=0 AND status IN(2,3,4) FOR UPDATE", id, tenant());
        if (!executed.isEmpty()) throw invalid("工单已有审批中、待检或完成报工，请先处理执行记录，不能直接取消");
        validateEmptyWip(id);
    }

    private void validateEmptyWip(Long id) {
        var wip = jdbc.queryForList("SELECT quantity FROM mes_wm_material_stock WHERE work_order_id=? AND tenant_id=? AND deleted=0 AND quantity>0 FOR UPDATE", id, tenant());
        if (!wip.isEmpty()) throw invalid("工单线边仍有库存，请先办理产品入库或退料");
    }
}
