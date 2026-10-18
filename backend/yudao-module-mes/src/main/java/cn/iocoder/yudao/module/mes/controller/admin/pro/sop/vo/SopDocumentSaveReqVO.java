package cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SopDocumentSaveReqVO {
    @NotBlank @Size(max = 64) private String code;
    @NotBlank @Size(max = 200) private String name;
    @NotNull private Long itemId;
    @NotNull private Long processId;
}
