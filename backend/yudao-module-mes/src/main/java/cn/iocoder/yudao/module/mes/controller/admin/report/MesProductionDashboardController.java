package cn.iocoder.yudao.module.mes.controller.admin.report;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.service.report.MesProductionDashboardService;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@RestController @RequestMapping("/mes/dashboard")
public class MesProductionDashboardController {
    @Resource private MesProductionDashboardService service;
    @GetMapping("/production") @PreAuthorize("@ss.hasPermission('mes:dashboard:query')")
    public CommonResult<Map<String,Object>> production(@RequestParam(required=false) Long workshopId,@RequestParam(required=false) Long workstationId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date){return success(service.production(workshopId,workstationId,date));}
}
