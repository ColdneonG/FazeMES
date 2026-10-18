package cn.iocoder.yudao.module.mes.service.pro.sop;

import cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import java.util.List;

public interface MesProSopService {
    Long createDocument(SopDocumentSaveReqVO request);
    List<MesSopDocumentDO> listDocuments(String keyword);
    Long saveVersion(SopVersionSaveReqVO request);
    List<MesSopVersionDO> listVersions(Long documentId);
    void transition(Long versionId, String action, String comment);
    /** Caller owns work-order then task locks, inside the task creation transaction. */
    SopTaskPackage lockForTask(MesProTaskDO task);
    /** Caller owns work-order then task locks, inside the report submission transaction. */
    void validateTaskExecution(Long taskId);
    SopTaskPackage getTaskPackage(Long taskId);
    void viewStep(SopExecutionReqVO request);
    void confirmStep(SopExecutionReqVO request);
}
