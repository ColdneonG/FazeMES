package cn.iocoder.yudao.module.mes.controller.admin.pro.kitting;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting.MesProReservationEventDO;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/pro/kitting")
@Validated
public class MesProKittingController {
    @Resource private MesProKittingService service;

    @GetMapping("/analyze")
    @PreAuthorize("@ss.hasPermission('mes:kitting:query')")
    public CommonResult<KittingAnalysisVO> analyze(@RequestParam Long workOrderId) { return success(service.analyze(workOrderId)); }

    @GetMapping("/board")
    @PreAuthorize("@ss.hasPermission('mes:kitting:query')")
    public CommonResult<List<KittingAnalysisVO>> board(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "true") boolean onlyShortage) { return success(service.board(keyword, onlyShortage)); }

    @PostMapping("/reserve")
    @PreAuthorize("@ss.hasPermission('mes:kitting:reserve')")
    public CommonResult<KittingAnalysisVO> reserve(@RequestParam Long workOrderId) { return success(service.reserve(workOrderId)); }

    @PostMapping("/release")
    @PreAuthorize("@ss.hasPermission('mes:kitting:release')")
    public CommonResult<Boolean> release(@RequestParam Long workOrderId) { service.release(workOrderId); return success(true); }

    @PutMapping("/followup")
    @PreAuthorize("@ss.hasPermission('mes:kitting:followup')")
    public CommonResult<Boolean> followup(@Valid @RequestBody KittingFollowupReqVO request) { service.saveFollowup(request); return success(true); }

    @GetMapping("/reservations")
    @PreAuthorize("@ss.hasPermission('mes:kitting:query')")
    public CommonResult<List<Map<String, Object>>> reservations(@RequestParam Long workOrderId) { return success(service.reservations(workOrderId)); }

    @GetMapping("/history")
    @PreAuthorize("@ss.hasPermission('mes:kitting:query')")
    public CommonResult<List<MesProReservationEventDO>> history(@RequestParam Long workOrderId) { return success(service.history(workOrderId)); }
}
