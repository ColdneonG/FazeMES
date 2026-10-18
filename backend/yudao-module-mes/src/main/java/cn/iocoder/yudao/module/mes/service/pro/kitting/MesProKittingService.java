package cn.iocoder.yudao.module.mes.service.pro.kitting;

import cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting.MesProReservationEventDO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface MesProKittingService {
    KittingAnalysisVO analyze(Long workOrderId);
    List<KittingAnalysisVO> board(String keyword, boolean onlyShortage);
    KittingAnalysisVO reserve(Long workOrderId);
    void release(Long workOrderId);
    void releaseForClosedOrder(Long workOrderId);
    void releaseExcessAfterIssue(Long workOrderId);
    /** Prelock issue and owned-reservation areas together before any stock movement. */
    void lockForIssue(Long workOrderId, List<Long> issueAreaIds);
    void validateAndConsumeOutbound(Long stockId, BigDecimal quantity, Long issueWorkOrderId, Long issueId);
    void saveFollowup(KittingFollowupReqVO request);
    List<Map<String, Object>> reservations(Long workOrderId);
    List<MesProReservationEventDO> history(Long workOrderId);
}
