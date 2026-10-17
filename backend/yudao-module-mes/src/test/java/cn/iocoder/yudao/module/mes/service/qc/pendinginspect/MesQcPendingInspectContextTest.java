package cn.iocoder.yudao.module.mes.service.qc.pendinginspect;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.qc.pendinginspect.vo.MesQcPendingInspectPageReqVO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.pendinginspect.MesQcPendingInspectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.mockito.Mockito.*;

class MesQcPendingInspectContextTest extends BaseMockitoUnitTest {
    @InjectMocks private MesQcPendingInspectServiceImpl service;
    @Mock private MesQcPendingInspectMapper mapper;
    @Test void forwardsStationOrderAndTaskContextToTheRealQuery() {
        service.getPendingInspectPage(new MesQcPendingInspectPageReqVO().setQcType(2).setItemId(3L)
                .setWorkstationId(4L).setWorkOrderId(5L).setTaskId(6L));
        verify(mapper).selectQcPendingPage(any(), isNull(), eq(2), eq(3L), eq(4L), eq(5L), eq(6L));
    }
}
