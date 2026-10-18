package cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class SopStep {
    private String id;
    private String title;
    private String instruction;
    private Boolean required = true;
    private Integer minViewSeconds = 0;
    private Boolean photoRequired = false;
    private List<String> attachments = new ArrayList<>();
    private List<Parameter> parameters = new ArrayList<>();

    @Data
    public static class Parameter {
        private String key;
        private String label;
        private Boolean required = true;
        private BigDecimal min;
        private BigDecimal max;
        private String unit;
    }
}
