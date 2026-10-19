package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;

/** Read-only Agent tool. It reuses the cost engine rather than asking a model to calculate money. */
@Service
public class MesInsightCostQuery {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]+(?:[_.-][A-Za-z0-9]+)*");
    private final JdbcTemplate jdbc;
    private final MesCostService costs;
    private final SecurityFrameworkService security;

    public MesInsightCostQuery(JdbcTemplate jdbc, MesCostService costs, SecurityFrameworkService security) {
        this.jdbc = jdbc; this.costs = costs; this.security = security;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> query(String question) {
        // Check before looking up records or building any external model context.
        if (!security.hasPermission("mes:cost:query")) throw invalid("查询价格、BOM 成本还需要价格与成本查询权限");
        Set<String> codes = new LinkedHashSet<>();
        var matcher = CODE.matcher(question);
        while (matcher.find()) codes.add(matcher.group().toUpperCase(Locale.ROOT));
        if (codes.size() > 20) throw invalid("请每次查询最多5个具体产品或工单编码");
        List<Map<String, Object>> items = new ArrayList<>();
        if (!codes.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(TenantContextHolder.getRequiredTenantId()); args.addAll(codes);
            var orders = jdbc.queryForList("SELECT id,code FROM mes_pro_work_order WHERE tenant_id=? AND deleted=0 AND UPPER(code) IN (" + placeholders + ") ORDER BY id LIMIT 6", args.toArray());
            var products = jdbc.queryForList("SELECT id,code FROM mes_md_item WHERE tenant_id=? AND deleted=0 AND UPPER(code) IN (" + placeholders + ") ORDER BY id LIMIT 6", args.toArray());
            if (orders.size() + products.size() > 5) throw invalid("匹配记录超过5条，请缩小产品或工单编码范围");
            for (var order : orders) {
                long id = ((Number) order.get("id")).longValue();
                var cost = costs.workorderCost(id);
                items.add(map("sourceType", "WORKORDER", "recordId", id, "code", order.get("code"),
                        "costBasis", "工单总额：冻结计划与实际分别核算", "status", cost.getStatus(),
                        "planTotalCost", amount(cost.getPlanTotalCost()), "totalCost", amount(cost.getActualTotalCost()),
                        "materialCost", amount(cost.getActualMaterialCost()), "laborCost", amount(cost.getActualLaborCost()),
                        "overheadCost", amount(cost.getActualOverheadCost()), "actualUnitCost", amount(cost.getActualUnitCost()),
                        "issues", cost.getIssues()));
            }
            for (var product : products) {
                long id = ((Number) product.get("id")).longValue();
                var cost = costs.product(id);
                items.add(map("sourceType", "PRODUCT", "recordId", id, "code", cost.getItemCode(), "name", cost.getItemName(),
                        "costBasis", "产品标准单位成本；不代表实际发生成本", "status", cost.getStatus(),
                        "totalCost", amount(cost.getTotalCost()), "materialCost", amount(cost.getMaterialCost()),
                        "laborCost", amount(cost.getLaborCost()), "overheadCost", amount(cost.getOverheadCost()),
                        "salePrice", amount(cost.getSalePrice()), "bomLines", cost.getChildren().size(),
                        "purchaseLeafCount", cost.getLeaves().size(), "issues", cost.getIssues()));
            }
        }
        return map("items", items, "currency", "CNY", "taxBasis", "EXCLUSIVE",
                "basis", "人民币、不含税。产品为标准单位成本，工单为冻结计划与实际总额；待核定不是零。只读查询，不冻结、不记账、不结算。多级 BOM 和明细请在价格与成本中心核对。",
                "hint", items.isEmpty() ? "未匹配到当前租户的具体编码，请输入产品或工单编码，例如：BROKY-FG-V2 的成本是多少？" : "");
    }

    private static String amount(BigDecimal value) { return value == null ? null : value.toPlainString(); }

    public static String answer(Map<String, Object> result) {
        @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) result.get("items");
        if (items.isEmpty()) return (String) result.get("hint");
        StringJoiner text = new StringJoiner("\n");
        for (var item : items) {
            String amount = item.get("totalCost") == null ? "待核定" : item.get("totalCost") + " 元";
            String label = "PRODUCT".equals(item.get("sourceType")) ? "标准单位成本" : "实际总成本";
            text.add(item.get("code") + "：" + label + " " + amount + "；状态 " + item.get("status") + "。");
        }
        text.add((String) result.get("basis"));
        return text.toString();
    }
}
