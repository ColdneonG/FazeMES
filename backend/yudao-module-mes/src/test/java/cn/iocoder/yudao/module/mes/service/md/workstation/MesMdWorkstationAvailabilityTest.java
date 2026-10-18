package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.machinery.MesDvMachineryDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.tm.tool.MesTmToolDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.machinery.MesDvMachineryMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.tm.tool.MesTmToolMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.module.system.api.dept.PostApi;
import cn.iocoder.yudao.module.system.api.dept.dto.PostRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMdWorkstationAvailabilityTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdWorkstationAvailabilityService service;
    @Mock private MesMdWorkstationMachineMapper machineMapper;
    @Mock private MesDvMachineryMapper machineryMapper;
    @Mock private MesMdWorkstationToolMapper toolMapper;
    @Mock private MesTmToolMapper toolLedgerMapper;
    @Mock private MesMdWorkstationWorkerMapper workerMapper;
    @Mock private MesMdWorkstationScheduleMapper scheduleMapper;
    @Mock private AdminUserApi adminUserApi;
    @Mock private PostApi postApi;
    private final LocalDateTime at = LocalDateTime.of(2026, 10, 17, 10, 0);
    private MesMdWorkstationDO station() { return new MesMdWorkstationDO().setId(1L).setStatus(0); }
    private void workers(List<Long> ids, int quantity) {
        when(workerMapper.selectListByWorkstationId(1L)).thenReturn(List.of(new MesMdWorkstationWorkerDO()
                .setPostId(9L).setQuantity(quantity).setUserIds(ids)));
        when(postApi.getPostMap(any())).thenReturn(Map.of(9L, new PostRespDTO().setId(9L).setName("装配").setStatus(0)));
    }
    private void enabledUser(Long id) {
        when(adminUserApi.getUser(id)).thenReturn(new AdminUserRespDTO().setId(id).setNickname("操作员" + id)
                .setStatus(0).setPostIds(Set.of(9L)));
    }
    private MesMdWorkstationScheduleMapper.PersonalShift shift(String day, String from, String to) {
        return new MesMdWorkstationScheduleMapper.PersonalShift().setPlanStart(at.toLocalDate().minusDays(1).atStartOfDay())
                .setPlanEnd(at.toLocalDate().atStartOfDay()).setDay(LocalDateTime.parse(day))
                .setStartTime(from).setEndTime(to);
    }

    @Test void legacyBindingsRemainUnrestrictedAndExplainIt() {
        workers(null, 5);
        when(toolMapper.selectListByWorkstationId(1L)).thenReturn(List.of(new MesMdWorkstationToolDO().setToolTypeId(3L).setQuantity(8)));
        var result = service.check(station(), at);
        assertTrue(result.getAvailable());
        assertEquals(2, result.getNotes().size());
        verifyNoInteractions(adminUserApi, scheduleMapper, toolLedgerMapper);
    }
    @Test void maintenanceDeviceBlocksProductionButIdleDeviceDoesNot() {
        when(machineMapper.selectListByWorkstationId(1L)).thenReturn(List.of(new MesMdWorkstationMachineDO().setMachineryId(7L)));
        when(machineryMapper.selectById(7L)).thenReturn(new MesDvMachineryDO().setName("车床").setStatus(3));
        assertTrue(service.check(station(), at).getReasons().get(0).contains("维护中"));
        when(machineryMapper.selectById(7L)).thenReturn(new MesDvMachineryDO().setName("车床").setStatus(1));
        assertTrue(service.check(station(), at).getAvailable());
        when(machineryMapper.selectById(7L)).thenReturn(null);
        assertFalse(service.check(station(), at).getAvailable());
    }
    @Test void toolQuantityExcludesMaintenanceScrapAndWrongType() {
        when(toolMapper.selectListByWorkstationId(1L)).thenReturn(List.of(new MesMdWorkstationToolDO()
                .setToolTypeId(3L).setQuantity(3).setToolIds(List.of(1L, 2L, 3L, 4L))));
        when(toolLedgerMapper.selectById(1L)).thenReturn(new MesTmToolDO().setToolTypeId(3L).setStatus(1).setAvailableQuantity(2));
        when(toolLedgerMapper.selectById(2L)).thenReturn(new MesTmToolDO().setToolTypeId(3L).setStatus(3).setAvailableQuantity(20));
        when(toolLedgerMapper.selectById(3L)).thenReturn(new MesTmToolDO().setToolTypeId(3L).setStatus(4).setAvailableQuantity(20));
        when(toolLedgerMapper.selectById(4L)).thenReturn(new MesTmToolDO().setToolTypeId(4L).setStatus(1).setAvailableQuantity(20));
        assertTrue(service.check(station(), at).getReasons().get(0).contains("需要 3，可用 2"));
        when(toolLedgerMapper.selectById(1L)).thenReturn(new MesTmToolDO().setToolTypeId(3L).setStatus(2).setAvailableQuantity(3));
        assertTrue(service.check(station(), at).getAvailable());
    }
    @Test void boundUsersRequireEnabledStatusAndCurrentPost() {
        workers(List.of(1L, 2L, 3L), 2); enabledUser(1L);
        when(adminUserApi.getUser(2L)).thenReturn(new AdminUserRespDTO().setId(2L).setStatus(1).setPostIds(Set.of(9L)));
        when(adminUserApi.getUser(3L)).thenReturn(new AdminUserRespDTO().setId(3L).setStatus(0).setPostIds(Set.of(8L)));
        var result = service.check(station(), at);
        assertFalse(result.getAvailable());
        assertTrue(result.getReasons().get(0).contains("需要 2，可用 1"));
        assertTrue(result.getNotes().get(0).contains("出勤时段未限制"));
    }
    @Test void confirmedPersonalScheduleUsesHalfOpenBoundaries() {
        workers(List.of(1L), 1); enabledUser(1L);
        when(scheduleMapper.selectPersonalShifts(eq(1L), any(), any())).thenReturn(List.of(shift("2026-10-17T00:00:00", "08:00", "16:00")));
        assertTrue(service.check(station(), at.withHour(8)).getAvailable());
        assertFalse(service.check(station(), at.withHour(16)).getAvailable());
        assertFalse(service.check(station(), at.withHour(7)).getAvailable());
    }
    @Test void overnightShiftBelongsToYesterdayAndRemainsAvailableAtMidnight() {
        when(scheduleMapper.selectPersonalShifts(eq(1L), any(), any())).thenReturn(List.of(shift("2026-10-16T00:00:00", "22:00", "06:00")));
        assertTrue(service.checkSchedule(1L, at.withHour(0)).available());
        assertFalse(service.checkSchedule(1L, at.withHour(6)).available());
    }
    @Test void noCurrentApplicablePlanIsUnrestrictedButConfirmedRestDayIsNot() {
        assertTrue(service.checkSchedule(1L, at).available());
        when(scheduleMapper.selectPersonalShifts(eq(1L), any(), any())).thenReturn(List.of(
                new MesMdWorkstationScheduleMapper.PersonalShift().setPlanStart(at.toLocalDate().atStartOfDay()).setPlanEnd(at.toLocalDate().atStartOfDay())));
        assertFalse(service.checkSchedule(1L, at).available());
        when(scheduleMapper.selectPersonalShifts(eq(1L), any(), any())).thenReturn(List.of(
                shift("2026-10-16T00:00:00", "08:00", "16:00").setPlanEnd(at.minusDays(1))));
        assertTrue(service.checkSchedule(1L, at).available());
    }
    @Test void invalidConfiguredShiftDoesNotGrantAvailability() {
        when(scheduleMapper.selectPersonalShifts(eq(1L), any(), any())).thenReturn(List.of(shift("2026-10-17T00:00:00", "08:00", "08:00")));
        assertFalse(service.checkSchedule(1L, at).available());
    }
}
