package cn.iocoder.yudao.module.mes.controller.admin.portal;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.mes.service.portal.MesCustomerPortalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/portal")
public class MesCustomerPortalAdminController {
    private final MesCustomerPortalService service;
    public MesCustomerPortalAdminController(MesCustomerPortalService service) { this.service = service; }
    @GetMapping("/accounts") @PreAuthorize("@ss.hasPermission('mes:portal:query')")
    public CommonResult<List<Map<String, Object>>> accounts() { return success(service.accounts()); }
    @PostMapping("/account/save") @PreAuthorize("@ss.hasPermission('mes:portal:manage')")
    public CommonResult<Long> saveAccount(@RequestBody MesCustomerPortalService.AccountSave body) { return success(service.saveAccount(body)); }
    @GetMapping("/clients") @PreAuthorize("@ss.hasPermission('mes:portal:query')")
    public CommonResult<List<Map<String, Object>>> clients() { return success(service.clients()); }
    @GetMapping("/products") @PreAuthorize("@ss.hasPermission('mes:portal:query')")
    public CommonResult<List<Map<String, Object>>> products() { return success(service.products()); }
    @GetMapping("/catalog") @PreAuthorize("@ss.hasPermission('mes:portal:query')")
    public CommonResult<List<Map<String, Object>>> catalog() { return success(service.adminCatalog()); }
    @PostMapping("/catalog/save") @PreAuthorize("@ss.hasPermission('mes:portal:manage')")
    public CommonResult<Boolean> saveCatalog(@RequestBody MesCustomerPortalService.CatalogSave body) { service.saveCatalog(body); return success(true); }
    @GetMapping("/requests") @PreAuthorize("@ss.hasPermission('mes:portal:query')")
    public CommonResult<List<Map<String, Object>>> requests() { return success(service.adminRequests()); }
    @PostMapping("/request/review") @PreAuthorize("@ss.hasPermission('mes:portal:manage')")
    public CommonResult<Boolean> review(@RequestBody MesCustomerPortalService.ReviewRequest body) { service.review(body, SecurityFrameworkUtils.getLoginUserId()); return success(true); }
}
