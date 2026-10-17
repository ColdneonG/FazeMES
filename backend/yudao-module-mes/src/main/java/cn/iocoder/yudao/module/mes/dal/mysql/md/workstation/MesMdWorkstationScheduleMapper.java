package cn.iocoder.yudao.module.mes.dal.mysql.md.workstation;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** Read confirmed personal calendars without treating absent calendars as a prohibition. */
@Mapper
public interface MesMdWorkstationScheduleMapper {
    @Select("""
        SELECT p.start_date AS plan_start, p.end_date AS plan_end,
               s.`day`, ps.start_time, ps.end_time
        FROM mes_cal_team_member m
        JOIN mes_cal_plan_team pt ON pt.team_id = m.team_id AND pt.tenant_id = m.tenant_id AND pt.deleted = 0
        JOIN mes_cal_plan p ON p.id = pt.plan_id AND p.tenant_id = m.tenant_id AND p.deleted = 0 AND p.status = 1
        LEFT JOIN mes_cal_team_shift s ON s.team_id = m.team_id AND s.plan_id = p.id
          AND s.tenant_id = m.tenant_id AND s.deleted = 0 AND s.`day` >= #{previousDay} AND s.`day` < #{nextDay}
        LEFT JOIN mes_cal_plan_shift ps ON ps.id = s.shift_id AND ps.plan_id = p.id
          AND ps.tenant_id = m.tenant_id AND ps.deleted = 0
        WHERE m.user_id = #{userId} AND m.deleted = 0
          AND p.start_date < #{nextDay} AND p.end_date >= #{previousDay}
        """)
    List<PersonalShift> selectPersonalShifts(@Param("userId") Long userId,
                                            @Param("previousDay") LocalDateTime previousDay,
                                            @Param("nextDay") LocalDateTime nextDay);

    @Data
    class PersonalShift {
        private LocalDateTime planStart;
        private LocalDateTime planEnd;
        private LocalDateTime day;
        private String startTime;
        private String endTime;
    }
}
