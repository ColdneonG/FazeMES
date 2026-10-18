package cn.iocoder.yudao.module.mes.controller.admin.report.vo;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.ArrayList;
import java.util.List;

/** Declarative data only. SQL, expressions, scripts and remote data sources are never accepted. */
@Data
public class MesReportConfig {
    private String dataset;
    private String chart = "table";
    private List<Column> columns = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    private List<String> groupBy = new ArrayList<>();
    private String sortField;
    private String sortDirection = "ASC";
    /** Optional native canvas; old report versions continue to use the original chart configuration. */
    private Canvas canvas;
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("不支持的报表配置属性：" + key);
    }
    @Data public static class Canvas {
        private Integer schemaVersion = 1;
        private String paper = "A4";
        private String orientation = "portrait";
        private String background = "#ffffff";
        private List<Block> blocks = new ArrayList<>();
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不支持的画布属性：" + key);
        }
    }
    @Data public static class Block {
        private String id;
        private String type;
        private Integer x;
        private Integer y;
        private Integer width;
        private Integer height;
        private String title = "";
        private String text = "";
        private BlockStyle style = new BlockStyle();
        private Binding binding = new Binding();
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不支持的组件属性：" + key);
        }
    }
    @Data public static class BlockStyle {
        private Integer fontSize = 12;
        private String color = "#183247";
        private String background = "#ffffff";
        private String align = "left";
        private Boolean bold = false;
        private Boolean border = false;
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不支持的样式属性：" + key);
        }
    }
    @Data public static class Binding {
        private String dimension;
        private List<String> metrics = new ArrayList<>();
        private String summary = "SUM";
        private Integer rowLimit = 10;
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不支持的数据绑定属性：" + key);
        }
    }
    @Data public static class Column {
        private String field;
        private String title;
        private Integer width = 160;
        private Boolean visible = true;
        private String aggregate;
    }
    @Data public static class Filter {
        private String field;
        private String operator = "eq";
        private String value;
    }
}
