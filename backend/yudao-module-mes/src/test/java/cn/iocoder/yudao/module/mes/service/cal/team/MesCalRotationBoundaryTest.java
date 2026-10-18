package cn.iocoder.yudao.module.mes.service.cal.team;
import cn.iocoder.yudao.module.mes.dal.dataobject.cal.plan.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.cal.team.*;
import cn.iocoder.yudao.module.mes.dal.mysql.cal.team.MesCalTeamShiftMapper;
import cn.iocoder.yudao.module.mes.service.cal.plan.*;
import cn.iocoder.yudao.module.mes.enums.cal.MesCalShiftMethodEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class MesCalRotationBoundaryTest {
    @InjectMocks MesCalTeamShiftServiceImpl service;
    @Mock MesCalTeamShiftMapper teamShiftMapper;
    @Mock MesCalPlanService planService;
    @Mock MesCalPlanShiftService planShiftService;
    @Mock MesCalPlanTeamService planTeamService;
    List<MesCalTeamShiftDO> generate(LocalDate date,int method) {
        var plan=new MesCalPlanDO().setId(1L).setStartDate(date.atStartOfDay()).setEndDate(date.plusDays(1).atStartOfDay())
            .setShiftType(2).setShiftMethod(method).setShiftCount(1);
        when(planService.getPlan(1L)).thenReturn(plan);
        when(planShiftService.getPlanShiftListByPlanId(1L)).thenReturn(List.of(new MesCalPlanShiftDO().setId(11L),new MesCalPlanShiftDO().setId(12L)));
        when(planTeamService.getPlanTeamListByPlanId(1L)).thenReturn(List.of(new MesCalPlanTeamDO().setTeamId(101L),new MesCalPlanTeamDO().setTeamId(102L)));
        List<MesCalTeamShiftDO> records=new ArrayList<>();
        doAnswer(a->{records.addAll(a.getArgument(0));return true;}).when(teamShiftMapper).insertBatch(anyCollection());
        service.generateTeamShiftRecords(1L);return records;
    }
    void assertRotation(List<MesCalTeamShiftDO> rows) {
        assertEquals(4,rows.size());
        rows.sort(Comparator.comparing(MesCalTeamShiftDO::getDay).thenComparing(MesCalTeamShiftDO::getSort));
        assertEquals(101L,rows.get(0).getTeamId());assertEquals(102L,rows.get(2).getTeamId());
        assertEquals(102L,rows.get(1).getTeamId());assertEquals(101L,rows.get(3).getTeamId());
    }
    @Test void sundayToMondayRotates() {assertRotation(generate(LocalDate.of(2026,10,18),MesCalShiftMethodEnum.WEEK.getMethod()));}
    @Test void monthBoundaryAcrossYearRotates() {assertRotation(generate(LocalDate.of(2026,12,31),MesCalShiftMethodEnum.MONTH.getMethod()));}
    @Test void quarterBoundaryAcrossYearRotates() {assertRotation(generate(LocalDate.of(2026,12,31),MesCalShiftMethodEnum.QUARTER.getMethod()));}
}
