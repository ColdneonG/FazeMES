package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.MesMdWorkstationAvailabilityRespVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.tm.tool.MesTmToolDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.machinery.MesDvMachineryMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.tm.tool.MesTmToolMapper;
import cn.iocoder.yudao.module.mes.service.cal.plan.MesCalShiftTimeRange;
import cn.iocoder.yudao.module.system.api.dept.PostApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Resource checks are shared by queries, scheduling and production feedback validation. */
@Service
public class MesMdWorkstationAvailabilityService {
    @Resource private MesMdWorkstationMachineMapper machineMapper;
    @Resource private MesDvMachineryMapper machineryMapper;
    @Resource private MesMdWorkstationToolMapper toolMapper;
    @Resource private MesTmToolMapper toolLedgerMapper;
    @Resource private MesMdWorkstationWorkerMapper workerMapper;
    @Resource private MesMdWorkstationScheduleMapper scheduleMapper;
    @Resource private AdminUserApi adminUserApi;
    @Resource private PostApi postApi;

    public MesMdWorkstationAvailabilityRespVO check(MesMdWorkstationDO station) {
        return check(station, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    public MesMdWorkstationAvailabilityRespVO check(MesMdWorkstationDO station, LocalDateTime at) {
        MesMdWorkstationAvailabilityRespVO result = new MesMdWorkstationAvailabilityRespVO()
                .setWorkstationId(station.getId()).setCheckedAt(at);
        if (!Objects.equals(station.getStatus(), 0)) result.getReasons().add("工作站已禁用");
        for (var resource : machineMapper.selectListByWorkstationId(station.getId())) {
            var machine = machineryMapper.selectById(resource.getMachineryId());
            if (machine == null) result.getReasons().add("绑定设备 " + resource.getMachineryId() + " 已不存在");
            else if (Objects.equals(machine.getStatus(), 3)) result.getReasons().add("设备「" + machine.getName() + "」维护中");
        }
        for (var resource : toolMapper.selectListByWorkstationId(station.getId())) {
            String label = "工装类型 " + resource.getToolTypeId();
            if (empty(resource.getToolIds())) {
                result.getNotes().add(label + "未绑定台账，可用数量未限制");
                continue;
            }
            int available = 0;
            for (Long id : new LinkedHashSet<>(resource.getToolIds())) {
                MesTmToolDO tool = toolLedgerMapper.selectById(id);
                if (tool != null && Objects.equals(tool.getToolTypeId(), resource.getToolTypeId())
                        && (Objects.equals(tool.getStatus(), 1) || Objects.equals(tool.getStatus(), 2))) {
                    available += Math.max(0, tool.getAvailableQuantity() == null ? 0 : tool.getAvailableQuantity());
                }
            }
            int required = positive(resource.getQuantity());
            if (available < required) result.getReasons().add(label + "可用数量不足（需要 " + required + "，可用 " + available + "；维修、报废及失效台账不计入）");
        }
        Set<Long> countedUsers = new HashSet<>();
        for (var resource : workerMapper.selectListByWorkstationId(station.getId())) {
            var post = postApi.getPostMap(Collections.singleton(resource.getPostId())).get(resource.getPostId());
            String label = "岗位「" + (post == null ? resource.getPostId() : post.getName()) + "」";
            if (empty(resource.getUserIds())) {
                result.getNotes().add(label + "未绑定实际人员，人数及个人排班未限制");
                continue;
            }
            if (post == null || !Objects.equals(post.getStatus(), 0)) {
                result.getReasons().add(label + "不存在或已停用");
                continue;
            }
            int available = 0;
            List<String> exclusions = new ArrayList<>();
            for (Long id : new LinkedHashSet<>(resource.getUserIds())) {
                var user = adminUserApi.getUser(id);
                if (user == null || !Objects.equals(user.getStatus(), 0)) {
                    exclusions.add("人员 " + id + "不存在或已停用"); continue;
                }
                if (user.getPostIds() == null || !user.getPostIds().contains(resource.getPostId())) {
                    exclusions.add(user.getNickname() + "已不属于该岗位"); continue;
                }
                if (countedUsers.contains(id)) { exclusions.add(user.getNickname() + "已计入其他岗位"); continue; }
                ScheduleStatus schedule = checkSchedule(id, at);
                if (!schedule.available()) { exclusions.add(user.getNickname() + "当前不在已确认班次内"); continue; }
                if (!schedule.restricted()) result.getNotes().add(user.getNickname() + "未配置当前适用的已确认排班，出勤时段未限制");
                countedUsers.add(id); available++;
            }
            int required = positive(resource.getQuantity());
            if (available < required) result.getReasons().add(label + "人数不足（需要 " + required + "，可用 " + available + "）"
                    + (exclusions.isEmpty() ? "" : "：" + String.join("；", exclusions)));
        }
        result.setAvailable(result.getReasons().isEmpty());
        return result;
    }

    ScheduleStatus checkSchedule(Long userId, LocalDateTime at) {
        var rows = scheduleMapper.selectPersonalShifts(userId, at.toLocalDate().minusDays(1).atStartOfDay(),
                at.toLocalDate().plusDays(1).atStartOfDay());
        boolean currentPlan = false;
        for (var row : rows) {
            if (row.getPlanStart() != null && row.getPlanEnd() != null
                    && !at.toLocalDate().isBefore(row.getPlanStart().toLocalDate())
                    && !at.toLocalDate().isAfter(row.getPlanEnd().toLocalDate())) currentPlan = true;
            if (row.getDay() == null || row.getStartTime() == null || row.getEndTime() == null) continue;
            // Ignore a corrupt/out-of-plan generated record instead of granting availability.
            if (row.getPlanStart() == null || row.getPlanEnd() == null
                    || row.getDay().toLocalDate().isBefore(row.getPlanStart().toLocalDate())
                    || row.getDay().toLocalDate().isAfter(row.getPlanEnd().toLocalDate())) continue;
            try {
                var range = MesCalShiftTimeRange.resolve(row.getDay().toLocalDate(), row.getStartTime(), row.getEndTime());
                if (!at.isBefore(range.startDateTime()) && at.isBefore(range.endDateTime())) return new ScheduleStatus(true, true);
            } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ignored) {
                // A configured but invalid shift does not make an employee available.
            }
        }
        return new ScheduleStatus(!currentPlan, currentPlan);
    }

    private static boolean empty(Collection<?> values) { return values == null || values.isEmpty(); }
    private static int positive(Integer value) { return value == null || value < 1 ? 1 : value; }
    record ScheduleStatus(boolean available, boolean restricted) { }
}
