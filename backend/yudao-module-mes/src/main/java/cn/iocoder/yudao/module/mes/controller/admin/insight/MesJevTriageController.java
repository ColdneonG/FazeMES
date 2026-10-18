package cn.iocoder.yudao.module.mes.controller.admin.insight;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.service.insight.MesJevTriageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController @RequestMapping("/mes/insight/jev") @Validated
public class MesJevTriageController {
    private final MesJevTriageService service;
    public MesJevTriageController(MesJevTriageService service){this.service=service;}
    public record Input(@NotBlank @Size(max=160) String title,@Size(max=2000) String description){}
    @GetMapping("/status") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')")
    public CommonResult<Map<String,Object>> status(){return success(service.status());}
    @GetMapping("/config") @PreAuthorize("@ss.hasPermission('mes:assistant:config')")
    public CommonResult<Map<String,Object>> config(){return success(service.settings());}
    @PutMapping("/config") @PreAuthorize("@ss.hasPermission('mes:assistant:config')") @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> save(@RequestBody MesJevTriageService.Settings req){return success(service.save(req));}
    @PostMapping("/test") @PreAuthorize("@ss.hasPermission('mes:assistant:config')") @ApiAccessLog(requestEnable=false)
    public CommonResult<Map<String,Object>> test(){return success(service.test());}
    @PostMapping("/suggest") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')") @ApiAccessLog(requestEnable=false)
    public CommonResult<Map<String,Object>> suggest(@Valid @RequestBody Input req){return success(service.suggest(req.title(),req.description()));}
}
