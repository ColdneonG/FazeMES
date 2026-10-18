package cn.iocoder.yudao.module.mes.service.dv.task;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.checkplan.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.checkrecord.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.maintenrecord.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.MesDvRepairLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.subject.MesDvSubjectDO;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.checkplan.MesDvCheckPlanMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.checkrecord.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.maintenrecord.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.task.*;
import cn.iocoder.yudao.module.mes.enums.dv.*;
import cn.iocoder.yudao.module.mes.service.dv.checkplan.*;
import cn.iocoder.yudao.module.mes.service.dv.machinery.MesDvMachineryService;
import cn.iocoder.yudao.module.mes.service.dv.repair.MesDvRepairService;
import cn.iocoder.yudao.module.mes.service.dv.subject.MesDvSubjectService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
/** Transactional task occurrences; the plan row serializes all generators and disable. */
@Service
public class MesDvTaskService {
    @Resource private MesDvCheckPlanMapper planMapper;
    @Resource private MesDvTaskMapper taskMapper;
    @Resource private MesDvTaskLineMapper lineMapper;
    @Resource private MesDvCheckPlanMachineryService planMachineryService;
    @Resource private MesDvCheckPlanSubjectService planSubjectService;
    @Resource private MesDvSubjectService subjectService;
    @Resource private MesDvMachineryService machineryService;
    @Resource private MesDvCheckRecordMapper checkRecordMapper;
    @Resource private MesDvCheckRecordLineMapper checkLineMapper;
    @Resource private MesDvMaintenRecordMapper maintenRecordMapper;
    @Resource private MesDvMaintenRecordLineMapper maintenLineMapper;
    @Resource private MesDvRepairService repairService;

    public static void validateCycle(MesDvCheckPlanDO p) {
        if (p.getCycleType()==null || p.getCycleType()<1 || p.getCycleType()>4
                || p.getCycleCount()==null || p.getCycleCount()<=0
                || (p.getStartDate()!=null && p.getEndDate()!=null && p.getEndDate().isBefore(p.getStartDate()))
                || (p.getType()==null || (p.getType()!=1 && p.getType()!=2))) {
            throw exception(DV_CHECK_PLAN_CYCLE_INVALID);
        }
    }
    /** Month/year recurrences remain anchored: Jan 31 -> Feb 28 -> Mar 31. */
    public static LocalDateTime nextDue(MesDvCheckPlanDO p, LocalDateTime due) {
        validateCycle(p);
        int count=p.getCycleCount();
        return switch (p.getCycleType()) {
            case 1 -> due.plusDays(count);
            case 2 -> due.plusWeeks(count);
            case 3 -> p.getStartDate().plusMonths((ChronoUnit.MONTHS.between(
                    YearMonth.from(p.getStartDate()),YearMonth.from(due))/count+1)*count);
            case 4 -> p.getStartDate().plusYears(((due.getYear()-p.getStartDate().getYear())/count+1L)*count);
            default -> throw exception(DV_CHECK_PLAN_CYCLE_INVALID);
        };
    }
    @Transactional(rollbackFor=Exception.class)
    public int generateDueTasks(Long planId, LocalDateTime now) {
        MesDvCheckPlanDO plan=planMapper.selectForUpdate(planId);
        if(plan==null || !Objects.equals(plan.getStatus(),MesDvCheckPlanStatusEnum.ENABLED.getStatus())) return 0;
        validateCycle(plan);
        LocalDateTime due=plan.getNextTaskTime();
        if(due==null) due=plan.getStartDate()!=null?plan.getStartDate():now.withNano(0);
        due=due.withNano(0); // MySQL DATETIME stores seconds; identical occurrence keys must use that precision.
        plan.setStartDate((plan.getStartDate()!=null?plan.getStartDate():due).withNano(0));
        if(due.isAfter(now) || (plan.getEndDate()!=null && due.isAfter(plan.getEndDate()))) return 0;
        var equipment=planMachineryService.getCheckPlanMachineryListByPlanId(planId);
        var subjects=planSubjectService.getCheckPlanSubjectListByPlanId(planId);
        if(equipment.isEmpty()) throw exception(DV_CHECK_PLAN_NO_MACHINERY);
        if(subjects.isEmpty()) throw exception(DV_CHECK_PLAN_NO_SUBJECT);
        int created=0,periods=0;
        // Bounded catch-up keeps one transaction small; cursor persists and the next scan resumes.
        while(!due.isAfter(now) && (plan.getEndDate()==null || !due.isAfter(plan.getEndDate())) && periods++<100) {
            for(var binding:equipment) {
                if(taskMapper.selectOccurrence(planId,binding.getMachineryId(),due)!=null) continue;
                var machine=machineryService.getMachinery(binding.getMachineryId());
                if(machine==null) throw exception(DV_MACHINERY_NOT_EXISTS);
                MesDvTaskDO task=new MesDvTaskDO().setPlanId(planId).setPlanName(plan.getName())
                    .setMachineryId(machine.getId()).setMachineryName(machine.getName()).setType(plan.getType())
                    .setDueTime(due).setStatus(0);
                taskMapper.insert(task);
                for(var bindingSubject:subjects) {
                    MesDvSubjectDO subject=subjectService.getSubject(bindingSubject.getSubjectId());
                    if(subject==null) throw exception(DV_SUBJECT_NOT_EXISTS);
                    lineMapper.insert(new MesDvTaskLineDO().setTaskId(task.getId()).setSubjectId(subject.getId())
                        .setSubjectName(subject.getName()).setContent(subject.getContent()).setStandard(subject.getStandard()));
                }
                created++;
            }
            due=nextDue(plan,due);
        }
        planMapper.updateById(new MesDvCheckPlanDO().setId(planId).setStartDate(plan.getStartDate()).setNextTaskTime(due));
        return created;
    }
    public PageResult<MesDvTaskDO> page(MesDvTaskPageReqVO q) {return taskMapper.selectPage(q);}
    public MesDvTaskRespVO get(Long id) {
        MesDvTaskDO task=taskMapper.selectById(id);
        if(task==null) throw exception(DV_TASK_NOT_EXISTS);
        return BeanUtils.toBean(task,MesDvTaskRespVO.class).setLines(lineMapper.selectByTask(id));
    }
    @Transactional(rollbackFor=Exception.class)
    public void claim(Long id,Long userId) {
        MesDvTaskDO task=lock(id);
        if(Objects.equals(task.getStatus(),1) && Objects.equals(task.getUserId(),userId)) return;
        if(!Objects.equals(task.getStatus(),0)) throw exception(DV_TASK_STATE_INVALID);
        taskMapper.updateById(new MesDvTaskDO().setId(id).setStatus(1).setUserId(userId).setClaimedTime(LocalDateTime.now()));
    }
    @Transactional(rollbackFor=Exception.class)
    public Long complete(MesDvTaskCompleteReqVO req,Long userId) {
        MesDvTaskDO task=lock(req.getId());
        if(!Objects.equals(task.getUserId(),userId)) throw exception(DV_TASK_NOT_OWNER);
        if(Objects.equals(task.getStatus(),2)) return task.getRecordId(); // retries do not duplicate records or repairs
        if(!Objects.equals(task.getStatus(),1)) throw exception(DV_TASK_NOT_OWNER);
        List<MesDvTaskLineDO> lines=lineMapper.selectByTask(task.getId());
        Map<Long,MesDvTaskCompleteReqVO.Line> answers=new HashMap<>();
        if(req.getLines()==null) throw exception(DV_TASK_RESULTS_INVALID);
        for(var answer:req.getLines()) {
            if(answer.getId()==null || answers.put(answer.getId(),answer)!=null
                || (answer.getResult()==null || (answer.getResult()!=1 && answer.getResult()!=2))
                || (answer.getResult()==2 && (answer.getDescription()==null || answer.getDescription().isBlank())))
                throw exception(DV_TASK_RESULTS_INVALID);
        }
        if(lines.isEmpty() || answers.size()!=lines.size() || lines.stream().anyMatch(l->!answers.containsKey(l.getId())))
            throw exception(DV_TASK_RESULTS_INVALID);
        LocalDateTime now=LocalDateTime.now();
        for(var line:lines) {
            var a=answers.get(line.getId());
            line.setResult(a.getResult()).setDescription(a.getDescription());
        }
        Long recordId;
        if(Objects.equals(task.getType(),MesDvCheckPlanTypeEnum.CHECK.getType())) {
            var record=new MesDvCheckRecordDO().setPlanId(task.getPlanId()).setMachineryId(task.getMachineryId())
                .setCheckTime(now).setUserId(userId).setStatus(MesDvCheckRecordStatusEnum.FINISHED.getStatus());
            checkRecordMapper.insert(record);recordId=record.getId();
            for(var l:lines) checkLineMapper.insert(new MesDvCheckRecordLineDO().setRecordId(recordId)
                .setSubjectId(l.getSubjectId()).setCheckStatus(l.getResult()).setCheckResult(l.getDescription()));
            machineryService.updateMachineryLastCheckTime(task.getMachineryId(),now);
        } else {
            var record=new MesDvMaintenRecordDO().setPlanId(task.getPlanId()).setMachineryId(task.getMachineryId())
                .setMaintenTime(now).setUserId(userId).setStatus(MesDvMaintenRecordStatusEnum.SUBMITTED.getStatus());
            maintenRecordMapper.insert(record);recordId=record.getId();
            for(var l:lines) maintenLineMapper.insert(new MesDvMaintenRecordLineDO().setRecordId(recordId)
                .setSubjectId(l.getSubjectId()).setStatus(l.getResult()).setResult(l.getDescription()));
            machineryService.updateMachineryLastMaintenTime(task.getMachineryId(),now);
        }
        List<MesDvRepairLineDO> abnormal=lines.stream().filter(l->Objects.equals(l.getResult(),2))
            .map(l->new MesDvRepairLineDO().setSubjectId(l.getSubjectId()).setMalfunction(l.getDescription())).toList();
        Long repairId=abnormal.isEmpty()?null:repairService.createAbnormalRepair(task.getMachineryId(),
            task.getType()==1?101:102,recordId,abnormal);
        for(var line:lines) lineMapper.updateById(line);
        taskMapper.updateById(new MesDvTaskDO().setId(task.getId()).setStatus(2).setCompletedTime(now)
            .setRecordId(recordId).setRepairId(repairId));
        return recordId;
    }
    private MesDvTaskDO lock(Long id) {
        MesDvTaskDO task=taskMapper.selectForUpdate(id);
        if(task==null) throw exception(DV_TASK_NOT_EXISTS);
        return task;
    }
}
