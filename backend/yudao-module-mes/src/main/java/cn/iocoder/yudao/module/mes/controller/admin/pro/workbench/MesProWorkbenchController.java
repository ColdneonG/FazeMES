package cn.iocoder.yudao.module.mes.controller.admin.pro.workbench;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo.*;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationMachineService;
import cn.iocoder.yudao.module.mes.service.dv.machinery.MesDvMachineryService;
import cn.iocoder.yudao.module.mes.service.pro.workbench.MesProWorkbenchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - MES 触屏工位工作台")
@RestController
@RequestMapping("/mes/pro/workbench")
public class MesProWorkbenchController {
    @Resource private MesProWorkbenchService service;
    @Resource private MesMdWorkstationService workstationService;
    @Resource private MesMdWorkstationMachineService workstationMachineService;
    @Resource private MesDvMachineryService machineryService;

    @GetMapping("/workstations")
    @PreAuthorize("@ss.hasPermission('mes:pro-task:query')")
    public CommonResult<List<WorkstationOption>> getWorkstations() {
        return success(workstationService.getWorkstationListByStatus(CommonStatusEnum.ENABLE.getStatus()).stream()
                .map(station -> new WorkstationOption().setId(station.getId()).setCode(station.getCode()).setName(station.getName())).toList());
    }

    @GetMapping("/tasks")
    @PreAuthorize("@ss.hasPermission('mes:pro-task:query')")
    public CommonResult<List<MesProWorkbenchTaskRespVO>> getTasks(@RequestParam("workstationId") Long workstationId) {
        return success(service.getTasks(workstationId));
    }

    @GetMapping("/machines")
    @PreAuthorize("@ss.hasPermission('mes:pro-task:query')")
    public CommonResult<List<WorkstationOption>> getMachines(@RequestParam("workstationId") Long workstationId) {
        workstationService.validateWorkstationExistsAndEnable(workstationId);
        java.util.Set<Long> ids = cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet(
                workstationMachineService.getWorkstationMachineListByWorkstationId(workstationId),
                cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationMachineDO::getMachineryId);
        return success(machineryService.getMachineryList(ids).stream()
                .map(machine -> new WorkstationOption().setId(machine.getId()).setCode(machine.getCode()).setName(machine.getName())).toList());
    }

    @GetMapping("/context")
    @PreAuthorize("@ss.hasPermission('mes:pro-task:query')")
    public CommonResult<MesProWorkbenchContextRespVO> getContext(@RequestParam("taskId") Long taskId) {
        return success(service.getContext(taskId));
    }

    @PostMapping("/feedback")
    @PreAuthorize("@ss.hasPermission('mes:pro-task:query') && @ss.hasPermission('mes:pro-feedback:create')")
    public CommonResult<Long> submitFeedback(@Valid @RequestBody MesProWorkbenchFeedbackReqVO reqVO) {
        return success(service.submitFeedback(reqVO));
    }

    @Data
    public static class WorkstationOption {
        private Long id;
        private String code;
        private String name;
    }
}
