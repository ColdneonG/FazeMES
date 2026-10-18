package cn.iocoder.yudao.module.mes.service.insight;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
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
}
