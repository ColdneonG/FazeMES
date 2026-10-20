package cn.iocoder.yudao.module.mes.service.insight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MesScheduleEngineTest {
    private final LocalDateTime monday=LocalDateTime.of(2026,10,19,8,0);
    private MesScheduleEngine.Job job(long id,long order,long station,double minutes,int sequence) {
        return new MesScheduleEngine.Job(id,order,station,"task"+id,monday,monday.plusDays(3),minutes,sequence);
    }
    @Test void workCalendarSkipsNightsAndWeekendsAndCountsExactBoundary() {
        var friday=LocalDateTime.of(2026,10,23,15,0);
        assertEquals(LocalDateTime.of(2026,10,26,9,0),MesScheduleEngine.advance(friday,120,8));
        assertEquals(friday.plusHours(1),MesScheduleEngine.advance(friday,60,8));
        assertEquals(monday,MesScheduleEngine.nextWorkingTime(monday.minusDays(1).plusHours(2),8));
        assertThrows(IllegalArgumentException.class,()->MesScheduleEngine.advance(monday,Double.NaN,8));
    }
    @Test void stationExclusionAndOperationPrecedenceAreBothRespected() {
        var plan=MesScheduleEngine.schedule(List.of(job(1,1,1,120,1),job(2,1,2,60,2),job(3,2,1,60,1)),monday,8,null);
        var byId=new HashMap<Long,MesScheduleEngine.Slot>();plan.slots().forEach(s->byId.put(s.job().id(),s));
        assertFalse(byId.get(2L).start().isBefore(byId.get(1L).end()));
        assertFalse(byId.get(3L).start().isBefore(byId.get(1L).end()));
        assertEquals(monday.plusHours(3),plan.finishes().get(1L));
    }
    @Test void priorityRevealsDisplacedOrdersAndOvertimeUsesActualExtraHours() {
        var jobs=List.of(job(1,1,1,480,1),job(2,2,1,120,1));
        var baseline=MesScheduleEngine.schedule(jobs,monday,8,null);
        var priority=MesScheduleEngine.schedule(jobs,monday,8,2L);
        assertTrue(priority.finishes().get(2L).isBefore(baseline.finishes().get(2L)));
        assertTrue(priority.finishes().get(1L).isAfter(baseline.finishes().get(1L)));
        var overtime=MesScheduleEngine.schedule(jobs,monday,10,2L);
        assertEquals(monday.plusHours(10),overtime.finishes().get(1L));
        assertEquals(2.0,overtime.overtimeHours(),.0001);
        assertEquals(0.0,baseline.overtimeHours());
    }

    private static Stream<Arguments> actualWorkingWindows() {
        var monday = LocalDateTime.of(2026,10,19,8,0);
        var tuesday = monday.plusDays(1);
        var friday = monday.plusDays(4).withHour(15);
        return Stream.of(
                Arguments.of("ordinary shift crosses a night", monday, 540, 8, tuesday.plusHours(1), 0.0),
                Arguments.of("extended shift crosses a night", monday, 660, 10, tuesday.plusHours(1), 2.0),
                Arguments.of("two complete extended shifts", monday, 1200, 10, tuesday.plusHours(10), 4.0),
                Arguments.of("weekend contains no work", friday, 240, 10, monday.plusWeeks(1).plusHours(1), 2.0),
                Arguments.of("minute boundary in ordinary shift", monday.withHour(15).withMinute(59), 2, 8, tuesday.plusMinutes(1), 0.0),
                Arguments.of("maximum extended shift crosses a night", monday, 780, 12, tuesday.plusHours(1), 4.0),
                Arguments.of("starts at overtime boundary", monday.withHour(16), 120, 10, monday.withHour(18), 2.0),
                Arguments.of("starts at closed shift boundary", monday.withHour(18), 1, 10, tuesday.plusMinutes(1), 0.0),
                Arguments.of("ends exactly at ordinary shift boundary", monday, 480, 8, monday.withHour(16), 0.0),
                Arguments.of("one real overtime minute", monday.withHour(15).withMinute(59), 2, 10, monday.withHour(16).plusMinutes(1), 1.0 / 60)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actualWorkingWindows")
    void overtimeIncludesOnlyScheduledWorkingMinutes(String description, LocalDateTime start, double minutes,
                                                     int dailyHours, LocalDateTime expectedEnd, double expectedOvertime) {
        var task = new MesScheduleEngine.Job(1,1,1,description,start,start.plusWeeks(2),minutes,1);
        var plan = MesScheduleEngine.schedule(List.of(task),start,dailyHours,null);
        assertEquals(expectedEnd,plan.finishes().get(1L));
        assertEquals(expectedOvertime,plan.slots().get(0).overtimeHours(),0.0000001);
        assertEquals(expectedOvertime,plan.overtimeHours(),0.0000001);
    }

    @Test void parallelStationsAccumulateResourceHoursEvenWhenCalendarWindowsOverlap() {
        var plan = MesScheduleEngine.schedule(List.of(job(1,1,1,600,1),job(2,2,2,600,1)),monday,10,1L);
        assertEquals(monday.plusHours(10),plan.finishes().get(1L));
        assertEquals(monday.plusHours(10),plan.finishes().get(2L));
        assertEquals(2.0,plan.slots().get(0).overtimeHours());
        assertEquals(2.0,plan.slots().get(1).overtimeHours());
        assertEquals(4.0,plan.overtimeHours());
    }
}
