package cn.iocoder.yudao.module.infra.controller.admin.monitor;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.infra.controller.admin.monitor.vo.*;
import cn.iocoder.yudao.module.infra.service.monitor.PlatformMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@Tag(name="管理后台 - 平台监控")
@RestController @RequestMapping("/infra/monitor")
public class PlatformMonitorController {
    @Resource private PlatformMonitorService monitorService;
    @GetMapping("/server") @Operation(summary="服务器及 JVM 实时指标")
    @PreAuthorize("@ss.hasPermission('infra:server:query')")
    public CommonResult<ServerMonitorRespVO> server() {return success(monitorService.server());}
    @GetMapping("/database") @Operation(summary="Druid 连接池和 SQL 汇总，不返回连接凭据或 SQL 原文")
    @PreAuthorize("@ss.hasPermission('infra:druid:query')")
    public CommonResult<DatabaseMonitorRespVO> database() {return success(monitorService.database());}
}
