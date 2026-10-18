package cn.iocoder.yudao.module.mes.controller.admin.cal.calendar;
import cn.iocoder.yudao.module.mes.controller.admin.cal.calendar.vo.*;
import cn.iocoder.yudao.module.mes.controller.admin.cal.team.vo.shift.MesCalTeamShiftListReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.cal.plan.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.cal.team.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.cal.holiday.*;
import cn.iocoder.yudao.module.mes.service.cal.plan.*;
import cn.iocoder.yudao.module.mes.service.cal.team.*;
import cn.iocoder.yudao.module.mes.service.cal.holiday.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class MesCalCalendarBoundaryTest {
    @InjectMocks MesCalCalendarController controller;
    @Mock MesCalTeamShiftService teamShiftService;
    @Mock MesCalTeamService teamService;
    @Mock MesCalPlanShiftService planShiftService;
    @Mock MesCalPlanService planService;
    @Mock MesCalTeamMemberService teamMemberService;
    @Mock MesCalHolidayService holidayService;
    LocalDateTime day=LocalDateTime.of(2026,12,31,0,0);
    MesCalCalendarListReqVO request(String type) {
        return new MesCalCalendarListReqVO().setQueryType(type).setUserId(7L).setTeamId(2L).setStartDay(day).setEndDay(day.plusDays(1));
    }
    void schedule() {
        when(teamShiftService.getTeamShiftList(any())).thenReturn(new ArrayList<>(List.of(
            new MesCalTeamShiftDO().setPlanId(1L).setTeamId(2L).setShiftId(3L).setSort(2).setDay(day),
            new MesCalTeamShiftDO().setPlanId(1L).setTeamId(2L).setShiftId(3L).setSort(2).setDay(day.plusDays(1)))));
        when(teamService.getTeamMap(anyCollection())).thenReturn(Map.of(2L,new MesCalTeamDO().setId(2L).setName("A")));
        when(planShiftService.getPlanShiftMap(anyCollection())).thenReturn(Map.of(3L,new MesCalPlanShiftDO().setId(3L).setName("night").setStartTime("20:00").setEndTime("08:00")));
        when(planService.getPlanMap(anyCollection())).thenReturn(Map.of(1L,new MesCalPlanDO().setId(1L).setShiftType(2)));
    }
    @Test void personalViewQueriesActualMembershipAndReturnsNextDayTimes() {
        schedule();when(teamMemberService.getTeamMemberByUserId(7L)).thenReturn(new MesCalTeamMemberDO().setUserId(7L).setTeamId(2L));
        var result=controller.getCalendarList(request("USER")).getData();
        assertEquals(2,result.size());var shift=result.get(0).getTeamShifts().get(0);
        assertEquals(LocalDateTime.of(2027,1,1,8,0),shift.getEndDateTime());assertEquals(720L,shift.getWorkingMinutes());
        var captor=ArgumentCaptor.forClass(MesCalTeamShiftListReqVO.class);
        verify(teamShiftService).getTeamShiftList(captor.capture());assertEquals(2L,captor.getValue().getTeamId());
    }
    @Test void holidayIsHiddenButExplicitWorkdayRemainsVisible() {
        schedule();when(holidayService.getHolidayList(day,day.plusDays(1))).thenReturn(List.of(
            new MesCalHolidayDO().setDay(day).setType(2),new MesCalHolidayDO().setDay(day.plusDays(1)).setType(1)));
        var result=controller.getCalendarList(request("TEAM")).getData();
        assertEquals(1,result.size());assertEquals(day.plusDays(1),result.get(0).getDay());
    }
    @Test void personWithoutTeamHasEmptyCalendar() {
        assertTrue(controller.getCalendarList(request("USER")).getData().isEmpty());
        verifyNoInteractions(teamShiftService,holidayService);
    }
}
