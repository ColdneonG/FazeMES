package cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.*;

@Data
public class SopExecutionReqVO {
    @NotNull private Long taskId;
    @NotBlank private String stepId;
    private Map<String, BigDecimal> parameters = new LinkedHashMap<>();
    private List<String> photos = new ArrayList<>();
}
