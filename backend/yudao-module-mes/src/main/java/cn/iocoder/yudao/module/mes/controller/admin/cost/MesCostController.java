package cn.iocoder.yudao.module.mes.controller.admin.cost;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.*;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="管理后台 - 制造价格与成本")
@RestController @RequestMapping("/mes/cost")
public class MesCostController {
    @Resource private MesCostService service;
    @GetMapping("/product") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<ProductCost> product(@RequestParam Long itemId){return success(service.product(itemId));}
    @GetMapping("/profile") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<Profile> profile(@RequestParam Long itemId){return success(service.profile(itemId));}
    @GetMapping("/profile-history") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<List<Profile>> history(@RequestParam Long itemId){return success(service.profileHistory(itemId));}
    @PutMapping("/profile") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> saveProfile(@Valid @RequestBody Profile req){service.saveProfile(req);return success(true);}
    @GetMapping("/workorder") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<WorkorderCost> order(@RequestParam Long workorderId){return success(service.workorderCost(workorderId));}
    @PostMapping("/workorder/snapshot") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> snapshot(@RequestParam Long workorderId){service.capturePlan(workorderId);return success(true);}
    @PostMapping("/workorder/effort") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> effort(@Valid @RequestBody Effort req){service.addEffort(req);return success(true);}
    @PostMapping("/workorder/effort/void") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> voidEffort(@Valid @RequestBody VoidEffort req){service.voidEffort(req);return success(true);}
    @PostMapping("/workorder/settle") @PreAuthorize("@ss.hasPermission('mes:cost:settle')")
    public CommonResult<Boolean> settle(@RequestParam Long workorderId){service.settle(workorderId);return success(true);}
    @GetMapping("/receipt-line-price") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<LinePrice> receiptPrice(@RequestParam Long lineId){return success(service.linePrice("RECEIPT",lineId));}
    @PutMapping("/receipt-line-price") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> receiptPrice(@Valid @RequestBody LinePrice req){service.saveLinePrice("RECEIPT",req);return success(true);}
    @GetMapping("/sales-line-price") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<LinePrice> salesPrice(@RequestParam Long lineId){return success(service.linePrice("SALES",lineId));}
    @PutMapping("/sales-line-price") @PreAuthorize("@ss.hasPermission('mes:cost:update')")
    public CommonResult<Boolean> salesPrice(@Valid @RequestBody LinePrice req){service.saveLinePrice("SALES",req);return success(true);}
    @GetMapping("/sales") @PreAuthorize("@ss.hasPermission('mes:cost:query')")
    public CommonResult<Sale> sales(@RequestParam Long salesId){return success(service.sales(salesId));}
}
