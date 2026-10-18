package cn.iocoder.yudao.module.mes.dal.dataobject.report;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class MesReportDefinitionDO {
    private Long id;
    private String name;
    private Integer latestVersion;
    private Integer publishedVersion;
    private Long tenantId;
    private LocalDateTime updateTime;
}
