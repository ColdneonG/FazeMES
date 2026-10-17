package cn.iocoder.yudao.module.mes.service.cal.plan;
import java.time.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.CAL_PLAN_SHIFT_TIME_INVALID;
/** A shift belongs to its start date. An earlier end clock means the following day. */
public record MesCalShiftTimeRange(LocalDateTime startDateTime,LocalDateTime endDateTime,
                                  long workingMinutes,boolean crossesMidnight) {
    public static MesCalShiftTimeRange resolve(LocalDate day,String start,String end) {
        try {
            if(start==null || end==null || !start.matches("[0-2][0-9]:[0-5][0-9]")
                || !end.matches("[0-2][0-9]:[0-5][0-9]")) throw new IllegalArgumentException();
            LocalTime from=LocalTime.parse(start),to=LocalTime.parse(end);
            if(from.equals(to)) throw new IllegalArgumentException();
            LocalDateTime begin=day.atTime(from),finish=day.atTime(to);
            boolean nextDay=!to.isAfter(from);
            if(nextDay) finish=finish.plusDays(1);
            return new MesCalShiftTimeRange(begin,finish,Duration.between(begin,finish).toMinutes(),nextDay);
        } catch(java.time.DateTimeException | IllegalArgumentException ex) {
            throw exception(CAL_PLAN_SHIFT_TIME_INVALID);
        }
    }
}
