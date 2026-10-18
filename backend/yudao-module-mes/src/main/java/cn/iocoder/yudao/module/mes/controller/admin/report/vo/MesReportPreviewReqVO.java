package cn.iocoder.yudao.module.mes.controller.admin.report.vo;
import lombok.Data;
@Data
public class MesReportPreviewReqVO {
    private Long id;
    private Integer version;
    private MesReportConfig config;
    private Integer pageNo = 1;
    private Integer pageSize = 50;
}
