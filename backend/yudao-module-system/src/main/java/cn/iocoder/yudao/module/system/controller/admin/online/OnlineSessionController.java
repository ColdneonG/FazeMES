package cn.iocoder.yudao.module.system.controller.admin.online;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.system.controller.admin.online.vo.*;
import cn.iocoder.yudao.module.system.service.online.OnlineSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@Tag(name="管理后台 - 在线登录会话")
@RestController @RequestMapping("/system/online-session")
public class OnlineSessionController {
    @Resource private OnlineSessionService sessionService;
    @GetMapping("/page") @Operation(summary="当前租户未过期的后台登录会话，不含令牌")
    @PreAuthorize("@ss.hasPermission('system:online-session:query')")
    public CommonResult<PageResult<OnlineSessionRespVO>> page(@Valid OnlineSessionPageReqVO q) {return success(sessionService.page(q));}
    @DeleteMapping("/revoke") @Operation(summary="按会话编号强退，撤销该刷新链的凭据")
    @PreAuthorize("@ss.hasPermission('system:online-session:revoke')")
    public CommonResult<Boolean> revoke(@RequestParam Long id) {return success(sessionService.revoke(id));}
}
