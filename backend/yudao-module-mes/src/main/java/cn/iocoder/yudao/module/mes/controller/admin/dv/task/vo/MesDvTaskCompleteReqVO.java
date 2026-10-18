package cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
@Data
public class MesDvTaskCompleteReqVO {
    @NotNull private Long id;
    @NotEmpty @Valid private List<Line> lines;
    @Data public static class Line {
        @NotNull private Long id;
        @NotNull @Min(1) @Max(2) private Integer result;
        @Size(max=2000) private String description;
    }
}
