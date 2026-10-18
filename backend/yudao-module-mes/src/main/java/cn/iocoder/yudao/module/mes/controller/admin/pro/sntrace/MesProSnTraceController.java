package cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace;

import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.MesProSnTraceDO;
import cn.iocoder.yudao.module.mes.service.pro.sntrace.MesProSnTraceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - MES 单件SN用料追溯")
@RestController
@RequestMapping("/mes/pro/sn-trace")
@Validated
public class MesProSnTraceController {
    @Resource private MesProSnTraceService service;

    @PostMapping("/register")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:create')")
    public CommonResult<Long> register(@Valid @RequestBody MesProSnTraceRegisterReqVO request) {
        return success(service.register(request));
    }

    @PostMapping("/station")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:station')")
    public CommonResult<Long> station(@Valid @RequestBody MesProSnTraceStationReqVO request) {
        return success(service.station(request));
    }

    @GetMapping("/page")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:query')")
    public CommonResult<PageResult<MesProSnTraceDO>> page(@Valid MesProSnTracePageReqVO request) {
        return success(service.page(request));
    }

    @GetMapping("/get")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:query')")
    public CommonResult<MesProSnTraceRespVO> get(@RequestParam("id") Long id) { return success(service.get(id)); }

    @GetMapping("/feedback-options")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:query')")
    public CommonResult<List<MesProFeedbackDO>> feedbackOptions(@RequestParam("workOrderId") Long workOrderId) {
        return success(service.feedbackOptions(workOrderId));
    }

    @GetMapping("/consume-options")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:query')")
    public CommonResult<List<MesProSnTraceRespVO.ConsumeOption>> consumeOptions(@RequestParam("feedbackId") Long feedbackId) {
        return success(service.consumeOptions(feedbackId));
    }

    @GetMapping("/affected")
    @PreAuthorize("@ss.hasPermission('mes:sn-trace:query')")
    public CommonResult<List<MesProSnTraceDO>> affected(@RequestParam(value = "batchId", required = false) Long batchId,
            @RequestParam(value = "batchCode", required = false) String batchCode) {
        return success(service.affected(batchId, batchCode));
    }
}
