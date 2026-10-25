package cn.iocoder.yudao.module.system.controller.admin.oauth2;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.online.OnlineSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuth2TokenControllerSecurityTest {
    private final OAuth2TokenService tokenService = mock(OAuth2TokenService.class);
    private final OnlineSessionService sessionService = mock(OnlineSessionService.class);
    private final OAuth2TokenController controller = new OAuth2TokenController();

    OAuth2TokenControllerSecurityTest() {
        ReflectionTestUtils.setField(controller, "oauth2TokenService", tokenService);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
    }

    @Test void legacyManagementPageDoesNotSerializeEitherCredential() {
        var query = new OAuth2AccessTokenPageReqVO();
        var token = new OAuth2AccessTokenDO().setId(12L).setAccessToken("secret-access")
                .setRefreshToken("secret-refresh").setUserId(5L).setUserType(2);
        when(tokenService.getAccessTokenPage(query)).thenReturn(new PageResult<>(List.of(token), 1L));
        String json = JsonUtils.toJsonString(controller.getAccessTokenPage(query));
        assertFalse(json.contains("secret-access"));
        assertFalse(json.contains("secret-refresh"));
        assertFalse(json.contains("accessToken"));
        assertFalse(json.contains("refreshToken"));
        assertTrue(json.contains("\"id\":12"));
    }

    @Test void bothDeleteRoutesUseTheTenantScopedRefreshChainRevocation() {
        when(sessionService.revoke(12L)).thenReturn(true);
        assertTrue(controller.deleteAccessToken(12L).getData());
        controller.deleteAccessTokenList(List.of(14L, 14L, 15L));
        verify(sessionService).revoke(12L);
        verify(sessionService).revoke(14L);
        verify(sessionService).revoke(15L);
        verifyNoInteractions(tokenService);
    }

    @Test void managementRoutesRetainTheirIndependentPermissions() throws Exception {
        assertTrue(OAuth2TokenController.class.getMethod("getAccessTokenPage", OAuth2AccessTokenPageReqVO.class)
                .getAnnotation(PreAuthorize.class).value().contains("system:oauth2-token:page"));
        for (var name : List.of("deleteAccessToken", "deleteAccessTokenList")) {
            var type = name.equals("deleteAccessToken") ? Long.class : List.class;
            assertTrue(OAuth2TokenController.class.getMethod(name, type).getAnnotation(PreAuthorize.class)
                    .value().contains("system:oauth2-token:delete"));
        }
    }
}
