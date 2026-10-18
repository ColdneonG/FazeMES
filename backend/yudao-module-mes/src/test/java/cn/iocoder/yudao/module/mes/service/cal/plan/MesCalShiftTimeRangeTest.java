package cn.iocoder.yudao.module.mes.service.cal.plan;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class MesCalShiftTimeRangeTest {
    @Test void overnightHasNextDayEndAndTwelveHoursAcrossYear() {
        var range=MesCalShiftTimeRange.resolve(LocalDate.of(2026,12,31),"20:00","08:00");
        assertEquals(LocalDateTime.of(2026,12,31,20,0),range.startDateTime());
        assertEquals(LocalDateTime.of(2027,1,1,8,0),range.endDateTime());
        assertEquals(720,range.workingMinutes());assertTrue(range.crossesMidnight());
    }
    @Test void midnightEndMeansNextDayButMidnightStartIsSameDay() {
        var middle=MesCalShiftTimeRange.resolve(LocalDate.of(2026,10,31),"16:00","00:00");
        assertEquals(LocalDateTime.of(2026,11,1,0,0),middle.endDateTime());assertEquals(480,middle.workingMinutes());
        var night=MesCalShiftTimeRange.resolve(LocalDate.of(2026,11,1),"00:00","08:00");
        assertFalse(night.crossesMidnight());assertEquals(480,night.workingMinutes());
    }
    @Test void equalTimesAndInvalidClocksAreRejected() {
        assertThrows(RuntimeException.class,()->MesCalShiftTimeRange.resolve(LocalDate.now(),"08:00","08:00"));
        assertThrows(RuntimeException.class,()->MesCalShiftTimeRange.resolve(LocalDate.now(),"24:00","08:00"));
        assertThrows(RuntimeException.class,()->MesCalShiftTimeRange.resolve(LocalDate.now(),"8:00","18:00"));
    }
}
