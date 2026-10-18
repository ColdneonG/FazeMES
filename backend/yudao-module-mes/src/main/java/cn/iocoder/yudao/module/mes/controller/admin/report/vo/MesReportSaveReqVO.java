package cn.iocoder.yudao.module.mes.controller.admin.report.vo;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class MesReportSaveReqVO {
    private Long id;
    @NotBlank @Size(max=100) private String name;
    @NotNull private MesReportConfig config;
    /** The version the editor loaded; concurrent edits must reload. */
    private Integer expectedVersion;
}
