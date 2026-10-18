package cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo;

import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopStepRecordDO;
import lombok.Data;
import java.util.*;

@Data
public class SopTaskPackage {
    private Long taskId;
    private Boolean required = false;
    private Long documentId;
    private Long versionId;
    private String versionNo;
    private String title;
    private String modelUrl;
    private List<String> attachments = new ArrayList<>();
    private List<SopStep> steps = new ArrayList<>();
    private List<MesSopStepRecordDO> records = new ArrayList<>();
}
