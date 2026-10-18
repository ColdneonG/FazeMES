package cn.iocoder.yudao.module.mes.service.pro.route;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.pro.route.vo.process.MesProRouteProcessSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.process.MesProProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.route.MesProRouteProcessMapper;
import cn.iocoder.yudao.module.mes.service.pro.process.MesProProcessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reject route configurations for which no inspection completion workflow exists. */
@ExtendWith(MockitoExtension.class)
class MesProRouteProcessInspectionTest {

    @InjectMocks private MesProRouteProcessServiceImpl service;
    @Mock private MesProRouteProcessMapper mapper;
    @Mock private MesProRouteService routeService;
    @Mock private MesProRouteProductService routeProductService;
    @Mock private MesProProcessService processService;

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    void nonKeyInspectionCannotBeCreatedOrSaved(Boolean key) {
        MesProRouteProcessSaveReqVO req = request(key, true);
        ServiceException create = assertThrows(ServiceException.class, () -> service.createRouteProcess(req));
        when(mapper.selectById(1L)).thenReturn(new MesProRouteProcessDO().setId(1L).setRouteId(10L).setKeyFlag(false).setCheckFlag(false));
        ServiceException update = assertThrows(ServiceException.class, () -> service.updateRouteProcess(req));
        assertEquals(PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED.getCode(), create.getCode());
        assertEquals(PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED.getCode(), update.getCode());
        verify(mapper, never()).insert(any(MesProRouteProcessDO.class));
        verify(mapper, never()).updateById(any(MesProRouteProcessDO.class));
        verifyNoInteractions(routeProductService, processService);
    }

    @Test
    void omittedInspectionFlagCannotRetainInspectionWhileTurningOffTheKeyFlag() {
        when(mapper.selectById(1L)).thenReturn(new MesProRouteProcessDO().setId(1L).setRouteId(10L).setKeyFlag(true).setCheckFlag(true));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.updateRouteProcess(request(false, null)));
        assertEquals(PRO_FEEDBACK_NON_KEY_CHECK_UNSUPPORTED.getCode(), failure.getCode());
        verify(mapper, never()).updateById(any(MesProRouteProcessDO.class));
    }

    @Test
    void supportedKeyInspectionAndNonInspectionConfigurationsRemainAvailable() {
        when(routeService.getRoute(10L)).thenReturn(new MesProRouteDO().setId(10L));
        when(processService.getProcess(20L)).thenReturn(new MesProProcessDO().setId(20L));

        service.createRouteProcess(request(true, true));
        service.createRouteProcess(request(false, false));

        verify(mapper, times(2)).insert(any(MesProRouteProcessDO.class));
    }

    @Test
    void updateCannotBypassOriginalRouteProtectionByMovingToAnotherRoute() {
        when(mapper.selectById(1L)).thenReturn(new MesProRouteProcessDO().setId(1L).setRouteId(99L).setKeyFlag(false).setCheckFlag(false));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.updateRouteProcess(request(false, false)));
        assertTrue(failure.getMessage().contains("另一条路线"));
        verify(mapper, never()).updateById(any(MesProRouteProcessDO.class));
    }

    private MesProRouteProcessSaveReqVO request(Boolean key, Boolean check) {
        MesProRouteProcessSaveReqVO req = new MesProRouteProcessSaveReqVO();
        req.setId(1L);
        req.setRouteId(10L);
        req.setProcessId(20L);
        req.setSort(1);
        req.setKeyFlag(key);
        req.setCheckFlag(check);
        return req;
    }
}
