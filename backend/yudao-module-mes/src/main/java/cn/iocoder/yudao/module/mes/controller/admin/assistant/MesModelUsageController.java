package cn.iocoder.yudao.module.mes.controller.admin.assistant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.vo.MesModelUsageVO.*;
import cn.iocoder.yudao.module.mes.service.assistant.MesModelUsageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/assistant")
@PreAuthorize("@ss.hasPermission('mes:assistant:config')")
public class MesModelUsageController {
    private final MesModelUsageService usage;
    public MesModelUsageController(MesModelUsageService usage){this.usage=usage;}
    @GetMapping("/usage-policy") public CommonResult<?> policy(){return success(usage.policy());}
    @PutMapping("/usage-policy") @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<?> save(@RequestBody PolicySaveRequest request){return success(usage.savePolicy(request));}
    @GetMapping("/usage-summary") public CommonResult<?> summary(){return success(usage.summary());}
    @GetMapping("/usage-page") public CommonResult<?> page(UsagePageRequest request){return success(usage.page(request));}
    @PostMapping("/usage-reconcile") @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<?> reconcile(@RequestBody ReconcileRequest request){return success(usage.reconcile(request));}
}
