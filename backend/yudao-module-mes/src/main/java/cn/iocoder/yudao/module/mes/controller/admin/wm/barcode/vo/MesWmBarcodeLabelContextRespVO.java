package cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo;

import lombok.Data;

@Data
public class MesWmBarcodeLabelContextRespVO {
    private Long barcodeId;
    private boolean registered;
    private Long configId;
    private Integer bizType;
    private Long bizId;
    private String bizCode;
    private String bizName;
    private Integer format;
    private String content;
    private String candidateContent;
    private String defaultTemplate;
}
