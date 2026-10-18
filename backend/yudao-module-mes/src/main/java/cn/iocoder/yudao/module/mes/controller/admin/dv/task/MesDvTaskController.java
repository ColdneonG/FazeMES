package cn.iocoder.yudao.module.mes.controller.admin.dv.task;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.MesDvTaskDO;
import cn.iocoder.yudao.module.mes.service.dv.task.MesDvTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
@Tag(name="管理后台 - 设备点检保养任务")
@RestController @RequestMapping("/mes/dv/task")
public class MesDvTaskController {
    @Resource private MesDvTaskService taskService;
    @GetMapping("/page") @Operation(summary="查询设备任务")
    @PreAuthorize("@ss.hasPermission('mes:dv-check-plan:query')")
    public CommonResult<PageResult<MesDvTaskDO>> page(@Valid MesDvTaskPageReqVO q) {return success(taskService.page(q));}
    @GetMapping("/get") @Operation(summary="任务及项目快照")
    @PreAuthorize("@ss.hasPermission('mes:dv-check-plan:query')")
    public CommonResult<MesDvTaskRespVO> get(@RequestParam Long id) {return success(taskService.get(id));}
    @PutMapping("/claim") @Operation(summary="当前人员领用任务")
    @PreAuthorize("@ss.hasAnyPermissions('mes:dv-check-record:update','mes:dv-mainten-record:update')")
    public CommonResult<Boolean> claim(@RequestParam Long id) {taskService.claim(id,getLoginUserId());return success(true);}
    @PutMapping("/complete") @Operation(summary="完成任务并登记结果，异常自动报修")
    @PreAuthorize("@ss.hasAnyPermissions('mes:dv-check-record:update','mes:dv-mainten-record:update')")
    public CommonResult<Long> complete(@Valid @RequestBody MesDvTaskCompleteReqVO req) {return success(taskService.complete(req,getLoginUserId()));}
    @PutMapping("/generate-due") @Operation(summary="补偿当前时间前到期的任务（停用方案不生成）")
    @PreAuthorize("@ss.hasPermission('mes:dv-check-plan:update')")
    public CommonResult<Integer> generateDue(@RequestParam Long planId) {
        return success(taskService.generateDueTasks(planId,java.time.LocalDateTime.now().withNano(0)));
    }
}
