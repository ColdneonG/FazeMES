package cn.iocoder.yudao.module.mes.service.dv.task;
import cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.checkplan.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.checkrecord.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.maintenrecord.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.machinery.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.subject.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.task.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.checkplan.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.checkrecord.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.maintenrecord.*;
import cn.iocoder.yudao.module.mes.service.dv.checkplan.*;
import cn.iocoder.yudao.module.mes.service.dv.machinery.*;
import cn.iocoder.yudao.module.mes.service.dv.subject.*;
import cn.iocoder.yudao.module.mes.service.dv.repair.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class MesDvTaskServiceTest {
    @InjectMocks MesDvTaskService service;
    @Mock MesDvCheckPlanMapper planMapper;
    @Mock MesDvTaskMapper taskMapper;
    @Mock MesDvTaskLineMapper lineMapper;
    @Mock MesDvCheckPlanMachineryService planMachineryService;
    @Mock MesDvCheckPlanSubjectService planSubjectService;
    @Mock MesDvSubjectService subjectService;
    @Mock MesDvMachineryService machineryService;
    @Mock MesDvCheckRecordMapper checkRecordMapper;
    @Mock MesDvCheckRecordLineMapper checkLineMapper;
    @Mock MesDvMaintenRecordMapper maintenRecordMapper;
    @Mock MesDvMaintenRecordLineMapper maintenLineMapper;
    @Mock MesDvRepairService repairService;
    MesDvCheckPlanDO plan() {
        return new MesDvCheckPlanDO().setId(1L).setName("daily").setType(1).setStatus(1)
            .setStartDate(LocalDateTime.of(2026,1,1,8,0)).setCycleType(1).setCycleCount(1);
    }
    void generation(MesDvCheckPlanDO p) {
        when(planMapper.selectForUpdate(1L)).thenReturn(p);
        when(planMachineryService.getCheckPlanMachineryListByPlanId(1L)).thenReturn(List.of(new MesDvCheckPlanMachineryDO().setMachineryId(2L)));
        when(planSubjectService.getCheckPlanSubjectListByPlanId(1L)).thenReturn(List.of(new MesDvCheckPlanSubjectDO().setSubjectId(3L)));
        lenient().when(machineryService.getMachinery(2L)).thenReturn(new MesDvMachineryDO().setId(2L).setName("machine"));
        lenient().when(subjectService.getSubject(3L)).thenReturn(new MesDvSubjectDO().setId(3L).setName("oil").setContent("check").setStandard("normal"));
    }
    @Test void catchUpAdvancesCursorAndRepeatedScanCreatesNothing() {
        var p=plan();generation(p);
        doAnswer(a->{p.setNextTaskTime(((MesDvCheckPlanDO)a.getArgument(0)).getNextTaskTime());return 1;}).when(planMapper).updateById(any(MesDvCheckPlanDO.class));
        assertEquals(3,service.generateDueTasks(1L,p.getStartDate().plusDays(2)));
        assertEquals(p.getStartDate().plusDays(3),p.getNextTaskTime());
        assertEquals(0,service.generateDueTasks(1L,p.getStartDate().plusDays(2)));
        verify(taskMapper,times(3)).insert(any(MesDvTaskDO.class));
        verify(lineMapper,times(3)).insert(any(MesDvTaskLineDO.class));
        var order=inOrder(planMapper,taskMapper);
        order.verify(planMapper).selectForUpdate(1L);
        order.verify(taskMapper).selectOccurrence(eq(1L),eq(2L),any());
    }
    @Test void existingOccurrenceIsSkippedAndCursorStillAdvances() {
        var p=plan();generation(p);
        when(taskMapper.selectOccurrence(1L,2L,p.getStartDate())).thenReturn(new MesDvTaskDO().setId(9L));
        assertEquals(0,service.generateDueTasks(1L,p.getStartDate()));
        verify(taskMapper,never()).insert(any(MesDvTaskDO.class));
        verify(planMapper).updateById(argThat((MesDvCheckPlanDO x)->x.getNextTaskTime().equals(p.getStartDate().plusDays(1))));
    }
    @Test void disabledPlanStopsNewTasks() {
        when(planMapper.selectForUpdate(1L)).thenReturn(plan().setStatus(0));
        assertEquals(0,service.generateDueTasks(1L,LocalDateTime.now()));
        verifyNoInteractions(taskMapper,lineMapper,planMachineryService);
    }
    @Test void failedGenerationDoesNotAdvanceCursor() {
        var p=plan();generation(p);
        doThrow(new IllegalStateException("snapshot write failed")).when(lineMapper).insert(any(MesDvTaskLineDO.class));
        assertThrows(IllegalStateException.class,()->service.generateDueTasks(1L,p.getStartDate()));
        verify(planMapper,never()).updateById(any(MesDvCheckPlanDO.class));
    }
    @Test void monthAndLeapYearRemainAnchored() {
        var p=plan().setStartDate(LocalDateTime.of(2024,1,31,8,0)).setCycleType(3);
        var feb=MesDvTaskService.nextDue(p,p.getStartDate());
        assertEquals(LocalDateTime.of(2024,2,29,8,0),feb);
        assertEquals(LocalDateTime.of(2024,3,31,8,0),MesDvTaskService.nextDue(p,feb));
        p.setStartDate(LocalDateTime.of(2024,2,29,8,0)).setCycleType(4);
        assertEquals(LocalDateTime.of(2025,2,28,8,0),MesDvTaskService.nextDue(p,p.getStartDate()));
        assertThrows(RuntimeException.class,()->MesDvTaskService.nextDue(p.setCycleCount(0),p.getStartDate()));
    }
    @Test void claimedByAnotherPersonCannotBeTakenOrCompleted() {
        when(taskMapper.selectForUpdate(1L)).thenReturn(new MesDvTaskDO().setId(1L).setStatus(1).setUserId(8L));
        assertThrows(RuntimeException.class,()->service.claim(1L,7L));
        var req=new MesDvTaskCompleteReqVO().setId(1L);
        assertThrows(RuntimeException.class,()->service.complete(req,7L));
        verifyNoInteractions(checkRecordMapper,maintenRecordMapper);
    }
    MesDvTaskCompleteReqVO answer(int result,String description) {
        return new MesDvTaskCompleteReqVO().setId(1L).setLines(List.of(new MesDvTaskCompleteReqVO.Line().setId(11L).setResult(result).setDescription(description)));
    }
    MesDvTaskDO execution(int type) {
        var task=new MesDvTaskDO().setId(1L).setPlanId(4L).setMachineryId(2L).setType(type).setStatus(1).setUserId(7L);
        when(taskMapper.selectForUpdate(1L)).thenReturn(task);
        when(lineMapper.selectByTask(1L)).thenReturn(List.of(new MesDvTaskLineDO().setId(11L).setSubjectId(3L)));
        return task;
    }
    @Test void abnormalTaskCreatesOriginalRecordAndOneSourceLinkedRepair() {
        var task=execution(1);
        doAnswer(a->{((MesDvCheckRecordDO)a.getArgument(0)).setId(90L);return 1;}).when(checkRecordMapper).insert(any(MesDvCheckRecordDO.class));
        when(repairService.createAbnormalRepair(eq(2L),eq(101),eq(90L),anyList())).thenReturn(91L);
        doAnswer(a->{var x=(MesDvTaskDO)a.getArgument(0);task.setStatus(x.getStatus()).setRecordId(x.getRecordId());return 1;}).when(taskMapper).updateById(any(MesDvTaskDO.class));
        assertEquals(90L,service.complete(answer(2,"oil leak"),7L));
        assertEquals(90L,service.complete(answer(2,"oil leak"),7L));
        verify(checkRecordMapper,times(1)).insert(any(MesDvCheckRecordDO.class));
        verify(repairService,times(1)).createAbnormalRepair(eq(2L),eq(101),eq(90L),argThat(ls->ls.size()==1 && ls.get(0).getMalfunction().equals("oil leak")));
        verify(taskMapper).updateById(argThat((MesDvTaskDO x)->x.getRepairId().equals(91L) && x.getStatus()==2));
    }
    @Test void normalMaintenanceCompletesWithoutRepair() {
        execution(2);
        doAnswer(a->{((MesDvMaintenRecordDO)a.getArgument(0)).setId(92L);return 1;}).when(maintenRecordMapper).insert(any(MesDvMaintenRecordDO.class));
        assertEquals(92L,service.complete(answer(1,null),7L));
        verify(maintenLineMapper).insert(argThat((MesDvMaintenRecordLineDO x)->x.getStatus()==1 && x.getRecordId()==92L));
        verifyNoInteractions(repairService,checkRecordMapper);
    }
    @Test void missingAbnormalDescriptionDoesNotCreateAnyRecord() {
        execution(1);
        assertThrows(RuntimeException.class,()->service.complete(answer(2," "),7L));
        verifyNoInteractions(checkRecordMapper,maintenRecordMapper,repairService);
    }
}
