package cn.iocoder.yudao.module.mes.service.pro.workbench;

import cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo.*;
import java.util.List;

public interface MesProWorkbenchService {
    List<MesProWorkbenchTaskRespVO> getTasks(Long workstationId);
    MesProWorkbenchContextRespVO getContext(Long taskId);
    Long submitFeedback(MesProWorkbenchFeedbackReqVO reqVO);
}
