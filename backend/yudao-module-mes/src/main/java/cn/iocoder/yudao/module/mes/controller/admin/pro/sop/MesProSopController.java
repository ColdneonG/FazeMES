package cn.iocoder.yudao.module.mes.controller.admin.pro.sop;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.*;
import cn.iocoder.yudao.module.mes.service.pro.sop.MesProSopService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 受控SOP")
@RestController
@RequestMapping("/mes/sop")
@Validated
public class MesProSopController {
    @Resource private MesProSopService service;

    @GetMapping("/document/list")
    @PreAuthorize("@ss.hasPermission('mes:sop:query')")
    public CommonResult<List<MesSopDocumentDO>> list(@RequestParam(required = false) String keyword) {
        return success(service.listDocuments(keyword));
    }

    @PostMapping("/document/create")
    @PreAuthorize("@ss.hasPermission('mes:sop:create')")
    public CommonResult<Long> create(@Valid @RequestBody SopDocumentSaveReqVO req) { return success(service.createDocument(req)); }

    @GetMapping("/version/list")
    @PreAuthorize("@ss.hasPermission('mes:sop:query')")
    public CommonResult<List<MesSopVersionDO>> versions(@RequestParam Long documentId) { return success(service.listVersions(documentId)); }

    @PostMapping("/version/save")
    @PreAuthorize("@ss.hasPermission('mes:sop:update')")
    public CommonResult<Long> save(@Valid @RequestBody SopVersionSaveReqVO req) { return success(service.saveVersion(req)); }

    @PostMapping("/version/submit")
    @PreAuthorize("@ss.hasPermission('mes:sop:submit')")
    public CommonResult<Boolean> submit(@Valid @RequestBody Action req) { return action(req, "submit"); }

    @PostMapping("/version/approve")
    @PreAuthorize("@ss.hasPermission('mes:sop:approve')")
    public CommonResult<Boolean> approve(@Valid @RequestBody Action req) { return action(req, "approve"); }

    @PostMapping("/version/reject")
    @PreAuthorize("@ss.hasPermission('mes:sop:approve')")
    public CommonResult<Boolean> reject(@Valid @RequestBody Action req) { return action(req, "reject"); }

    @PostMapping("/version/publish")
    @PreAuthorize("@ss.hasPermission('mes:sop:publish')")
    public CommonResult<Boolean> publish(@Valid @RequestBody Action req) { return action(req, "publish"); }

    @PostMapping("/version/disable")
    @PreAuthorize("@ss.hasPermission('mes:sop:disable')")
    public CommonResult<Boolean> disable(@Valid @RequestBody Action req) { return action(req, "disable"); }

    @GetMapping("/task")
    @PreAuthorize("@ss.hasAnyPermissions('mes:sop:execute', 'mes:pro-task:query')")
    public CommonResult<SopTaskPackage> task(@RequestParam Long taskId) { return success(service.getTaskPackage(taskId)); }

    @PostMapping("/task/view")
    @PreAuthorize("@ss.hasPermission('mes:sop:execute')")
    public CommonResult<Boolean> view(@Valid @RequestBody SopExecutionReqVO req) { service.viewStep(req); return success(true); }

    @PostMapping("/task/confirm")
    @PreAuthorize("@ss.hasPermission('mes:sop:execute')")
    public CommonResult<Boolean> confirm(@Valid @RequestBody SopExecutionReqVO req) { service.confirmStep(req); return success(true); }

    private CommonResult<Boolean> action(Action req, String action) {
        service.transition(req.getId(), action, req.getComment());
        return success(true);
    }

    @Data
    public static class Action {
        @NotNull private Long id;
        @Size(max = 2000) private String comment;
    }
}
