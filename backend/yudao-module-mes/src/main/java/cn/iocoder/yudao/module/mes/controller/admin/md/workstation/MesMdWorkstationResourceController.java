package cn.iocoder.yudao.module.mes.controller.admin.md.workstation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.MesMdWorkstationAvailabilityRespVO;
import cn.iocoder.yudao.module.mes.dal.mysql.tm.tool.MesTmToolMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.tm.tool.MesTmToolDO;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationAvailabilityService;
import cn.iocoder.yudao.module.mes.service.md.workstation.MesMdWorkstationService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - MES 工作站资源可用性")
@RestController
@RequestMapping("/mes/md-workstation")
public class MesMdWorkstationResourceController {
    @Resource private MesMdWorkstationService workstationService;
    @Resource private MesMdWorkstationAvailabilityService availabilityService;
    @Resource private AdminUserApi adminUserApi;
    @Resource private MesTmToolMapper toolMapper;

    @GetMapping("/availability")
    @Operation(summary = "获得工作站当前资源可用性及原因")
    @PreAuthorize("@ss.hasAnyPermissions('mes:md-workstation:query','mes:pro-task:query')")
    public CommonResult<MesMdWorkstationAvailabilityRespVO> availability(@RequestParam Long id) {
        return success(availabilityService.check(workstationService.validateWorkstationExists(id)));
    }

    @GetMapping("/worker-candidates")
    @Operation(summary = "获得所选岗位可绑定的启用人员")
    @PreAuthorize("@ss.hasPermission('mes:md-workstation:update')")
    public CommonResult<List<WorkerCandidate>> workers(@RequestParam Long postId) {
        return success(adminUserApi.getUserListByPostIds(Collections.singleton(postId)).stream()
                .filter(user -> Objects.equals(user.getStatus(), 0))
                .map(user -> new WorkerCandidate(user.getId(), user.getNickname())).toList());
    }

    @GetMapping("/tool-candidates")
    @Operation(summary = "获得所选工装类型的台账及可用数量")
    @PreAuthorize("@ss.hasPermission('mes:md-workstation:update')")
    public CommonResult<List<ToolCandidate>> tools(@RequestParam Long toolTypeId) {
        return success(toolMapper.selectList(MesTmToolDO::getToolTypeId, toolTypeId).stream()
                .map(tool -> new ToolCandidate(tool.getId(), tool.getCode(), tool.getName(),
                        tool.getAvailableQuantity(), tool.getStatus())).toList());
    }

    public record WorkerCandidate(Long id, String nickname) { }
    public record ToolCandidate(Long id, String code, String name, Integer availableQuantity, Integer status) { }
}
