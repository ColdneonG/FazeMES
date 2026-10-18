package cn.iocoder.yudao.module.mes.controller.app.portal;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.mes.service.portal.MesCustomerPortalService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** All protected operations authenticate their independent opaque customer token in the service. */
@RestController
@RequestMapping("/mes/customer-portal")
@TenantIgnore
public class AppCustomerPortalController {
    private final MesCustomerPortalService service;
    public AppCustomerPortalController(MesCustomerPortalService service) { this.service = service; }

    @PostMapping("/login") @PermitAll @ApiAccessLog(enable=false)
    public CommonResult<Map<String, Object>> login(@RequestBody MesCustomerPortalService.LoginRequest body, HttpServletRequest request) {
        return success(service.login(body, request.getRemoteAddr()));
    }
    @GetMapping("/me") @PermitAll
    public CommonResult<Map<String, Object>> me(@RequestHeader(value="X-Portal-Token", required=false) String token) { return success(service.me(token)); }
    @PostMapping("/logout") @PermitAll
    public CommonResult<Boolean> logout(@RequestHeader(value="X-Portal-Token", required=false) String token) { service.logout(token); return success(true); }
    @GetMapping("/catalog") @PermitAll
    public CommonResult<List<Map<String, Object>>> catalog(@RequestHeader(value="X-Portal-Token", required=false) String token) { return success(service.catalog(token)); }
    @GetMapping("/orders") @PermitAll
    public CommonResult<List<Map<String, Object>>> orders(@RequestHeader(value="X-Portal-Token", required=false) String token) { return success(service.orders(token)); }
    @GetMapping("/requests") @PermitAll
    public CommonResult<List<Map<String, Object>>> requests(@RequestHeader(value="X-Portal-Token", required=false) String token) { return success(service.requests(token)); }
    @PostMapping("/requests") @PermitAll
    public CommonResult<Map<String, Object>> submit(@RequestHeader(value="X-Portal-Token", required=false) String token, @RequestBody MesCustomerPortalService.OrderRequest body) { return success(service.submit(token, body)); }
}
