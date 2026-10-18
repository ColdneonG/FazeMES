package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import static cn.iocoder.yudao.module.mes.service.report.MesReportErrorConstants.invalid;

/** Declarative layout only: no CSS strings, HTML, script, URL or query providers. */
public final class MesReportCanvasValidator {
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Set<String> TYPES = Set.of("text", "table", "bar", "line", "pie", "metric", "divider");
    private MesReportCanvasValidator() {}

    public static void validate(MesReportConfig config, List<Map<String, Object>> columns) {
        var canvas = config.getCanvas();
        if (canvas == null) return;
        if (!Integer.valueOf(1).equals(canvas.getSchemaVersion()) || !"A4".equals(canvas.getPaper())
                || !Set.of("portrait", "landscape").contains(canvas.getOrientation() == null ? "" : canvas.getOrientation())) {
            throw invalid("画布只支持版本1的A4纵向或横向页面");
        }
        color(canvas.getBackground());
        if (canvas.getBlocks() == null || canvas.getBlocks().size() > 30 || canvas.getBlocks().stream().anyMatch(Objects::isNull)) {
            throw invalid("画布最多30个组件，组件不能为空");
        }
        int pageWidth = "landscape".equals(canvas.getOrientation()) ? 1123 : 794;
        int pageHeight = "landscape".equals(canvas.getOrientation()) ? 794 : 1123;
        Set<String> ids = new HashSet<>();
        Set<String> visible = new HashSet<>();
        Set<String> numeric = new HashSet<>();
        for (var column : columns) {
            visible.add((String) column.get("field"));
            if ("n".equals(column.get("type"))) numeric.add((String) column.get("field"));
        }
        for (var block : canvas.getBlocks()) {
            if (block.getId() == null || !ID.matcher(block.getId()).matches() || !ids.add(block.getId())) throw invalid("组件标识无效或重复");
            if (block.getType() == null || !TYPES.contains(block.getType())) throw invalid("不支持的画布组件");
            range(block.getX(), 0, pageWidth - 40, "横坐标");
            range(block.getY(), 0, pageHeight - 16, "纵坐标");
            range(block.getWidth(), 40, pageWidth - block.getX(), "宽度");
            range(block.getHeight(), 16, pageHeight - block.getY(), "高度");
            if (block.getTitle() == null || block.getTitle().length() > 100 || block.getText() == null || block.getText().length() > 4000) throw invalid("组件标题最多100字，文本最多4000字");
            var style = block.getStyle();
            if (style == null) throw invalid("组件样式不能为空");
            range(style.getFontSize(), 8, 72, "字号");
            color(style.getColor()); color(style.getBackground());
            if (!Set.of("left", "center", "right").contains(style.getAlign() == null ? "" : style.getAlign()) || style.getBold() == null || style.getBorder() == null) throw invalid("无效文字样式");
            var binding = block.getBinding();
            if (binding == null || binding.getMetrics() == null || binding.getMetrics().size() > 5) throw invalid("每个组件最多绑定5个数值列");
            if (binding.getDimension() != null && !visible.contains(binding.getDimension())) throw invalid("维度必须绑定已显示的业务白名单列");
            Set<String> metrics = new HashSet<>(binding.getMetrics());
            if (metrics.size() != binding.getMetrics().size() || !numeric.containsAll(metrics)) throw invalid("指标必须绑定已显示的数值列，且不得重复");
            if (!Set.of("SUM", "AVG", "MIN", "MAX", "COUNT").contains(binding.getSummary() == null ? "" : binding.getSummary())) throw invalid("不支持的组件汇总方式");
            range(binding.getRowLimit(), 1, 100, "组件显示行数");
        }
    }

    private static void color(String color) {
        if (color == null || !COLOR.matcher(color).matches()) throw invalid("颜色必须是6位十六进制色值");
    }
    private static void range(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) throw invalid("组件" + label + "超出允许范围");
    }
}
