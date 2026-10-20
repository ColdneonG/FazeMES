package cn.iocoder.yudao.module.mes.controller.admin.pro.workorder;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderLossVO.CloseRequest;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderLossVO.Record;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderLossService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/pro/work-order")
@Validated
public class MesProWorkOrderLossController {
    @Resource private MesProWorkOrderLossService lossService;
    @GetMapping("/loss-preview")
    @Operation(summary = "预览前序损耗结案数量")
    @PreAuthorize("@ss.hasPermission('mes:pro-work-order:update')")
    public CommonResult<Record> preview(@RequestParam("id") Long id) { return success(lossService.preview(id)); }
    @PutMapping("/close-with-loss")
    @Operation(summary = "记录前序损耗并结案工单，不结算不良成本")
    @PreAuthorize("@ss.hasPermission('mes:pro-work-order:update')")
    public CommonResult<Boolean> close(@Valid @RequestBody CloseRequest request) { lossService.close(request); return success(true); }
    @GetMapping("/loss-record")
    @Operation(summary = "获得工单损耗结案审计记录")
    @PreAuthorize("@ss.hasPermission('mes:pro-work-order:query')")
    public CommonResult<Record> record(@RequestParam("id") Long id) { return success(lossService.record(id)); }
}
