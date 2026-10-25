package cn.iocoder.yudao.module.system.controller.admin.oauth2;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenPageReqVO;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.service.online.OnlineSessionService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - OAuth2.0 令牌")
@RestController
@RequestMapping("/system/oauth2-token")
@Validated
public class OAuth2TokenController {

    @Resource
    private OAuth2TokenService oauth2TokenService;
    @Resource
    private OnlineSessionService sessionService;

    @GetMapping("/page")
    @Operation(summary = "获得访问令牌分页", description = "只返回有效期内的")
    @PreAuthorize("@ss.hasPermission('system:oauth2-token:page')")
    public CommonResult<PageResult<OAuth2AccessTokenRespVO>> getAccessTokenPage(@Valid OAuth2AccessTokenPageReqVO reqVO) {
        PageResult<OAuth2AccessTokenDO> pageResult = oauth2TokenService.getAccessTokenPage(reqVO);
        return success(BeanUtils.toBean(pageResult, OAuth2AccessTokenRespVO.class));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除访问令牌")
    @Parameter(name = "id", description = "会话记录编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:oauth2-token:delete')")
    public CommonResult<Boolean> deleteAccessToken(@RequestParam("id") @Positive Long id) {
        // Reuse the tenant-scoped, refresh-race-safe revocation path. Credentials never leave the server.
        return success(sessionService.revoke(id));
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除访问令牌")
    @Parameter(name = "ids", description = "会话记录编号数组，最多100项", required = true)
    @PreAuthorize("@ss.hasPermission('system:oauth2-token:delete')")
    public CommonResult<Boolean> deleteAccessTokenList(
            @RequestParam("ids") @NotEmpty @Size(max = 100) List<@Positive Long> ids) {
        ids.stream().distinct().forEach(sessionService::revoke);
        return success(true);
    }

}
