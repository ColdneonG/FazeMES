package cn.iocoder.yudao.module.mes.service.pro.sntrace;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.MesProSnTraceDO;
import java.util.Collection;
import java.util.List;

public interface MesProSnTraceService {
    Long register(MesProSnTraceRegisterReqVO request);
    Long station(MesProSnTraceStationReqVO request);
    PageResult<MesProSnTraceDO> page(MesProSnTracePageReqVO request);
    MesProSnTraceRespVO get(Long id);
    List<MesProFeedbackDO> feedbackOptions(Long workOrderId);
    List<MesProSnTraceRespVO.ConsumeOption> consumeOptions(Long feedbackId);
    List<MesProSnTraceDO> affected(Long batchId, String batchCode);
    void ensureSnUntracked(Collection<Long> snIds);
}
