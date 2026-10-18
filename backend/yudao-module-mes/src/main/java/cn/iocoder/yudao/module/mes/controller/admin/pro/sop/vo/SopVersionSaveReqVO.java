package cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class SopVersionSaveReqVO {
    private Long id;
    @NotNull private Long documentId;
    @NotBlank @Size(max = 64) private String versionNo;
    @NotBlank @Size(max = 200) private String title;
    private String changeNote;
    private String modelUrl;
    private List<String> attachments = new ArrayList<>();
    @NotNull @Size(min = 1, max = 200) private List<SopStep> steps;
}
