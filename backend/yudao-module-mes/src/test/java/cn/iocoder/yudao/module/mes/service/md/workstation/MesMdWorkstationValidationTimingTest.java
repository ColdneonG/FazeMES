package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.MesMdWorkstationAvailabilityRespVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.MesMdWorkstationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import java.util.List;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMdWorkstationValidationTimingTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdWorkstationServiceImpl service;
    @Mock private MesMdWorkstationMapper mapper;
    @Mock private MesMdWorkstationAvailabilityService availabilityService;
    private MesMdWorkstationDO enabled() {
        var station = new MesMdWorkstationDO().setId(1L).setStatus(0);
        when(mapper.selectById(1L)).thenReturn(station); return station;
    }
    @Test void futurePlanningAndReadQueriesValidateEnabledWithoutConsultingTodaysShift() {
        var station = enabled();
        assertSame(station, service.validateWorkstationExistsAndEnable(1L));
        verifyNoInteractions(availabilityService);
    }
    @Test void missingStationStillFailsForBothValidationModes() {
        assertEquals(MD_WORKSTATION_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> service.validateWorkstationExistsAndEnable(1L)).getCode());
        assertEquals(MD_WORKSTATION_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> service.validateWorkstationAvailable(1L)).getCode());
        verifyNoInteractions(availabilityService);
    }
    @Test void disabledStationStillFailsForBothModesBeforeResourceQueries() {
        when(mapper.selectById(1L)).thenReturn(new MesMdWorkstationDO().setId(1L).setStatus(1));
        assertEquals(MD_WORKSTATION_IS_DISABLE.getCode(), assertThrows(ServiceException.class,
                () -> service.validateWorkstationExistsAndEnable(1L)).getCode());
        assertEquals(MD_WORKSTATION_IS_DISABLE.getCode(), assertThrows(ServiceException.class,
                () -> service.validateWorkstationAvailable(1L)).getCode());
        verifyNoInteractions(availabilityService);
    }
    @Test void productionValidationRejectsCurrentUnavailabilityAndPreservesTheReason() {
        var station = enabled();
        when(availabilityService.check(station)).thenReturn(new MesMdWorkstationAvailabilityRespVO()
                .setAvailable(false).setReasons(List.of("设备维护中", "当前班次人数不足")));
        var error = assertThrows(ServiceException.class, () -> service.validateWorkstationAvailable(1L));
        assertEquals(MD_WORKSTATION_RESOURCE_UNAVAILABLE.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("设备维护中")); assertTrue(error.getMessage().contains("当前班次人数不足"));
        verify(availabilityService).check(station);
    }
    @Test void productionValidationAcceptsCurrentlyAvailableStation() {
        var station = enabled();
        when(availabilityService.check(station)).thenReturn(new MesMdWorkstationAvailabilityRespVO().setAvailable(true));
        assertSame(station, service.validateWorkstationAvailable(1L));
        verify(availabilityService).check(station);
    }
}
