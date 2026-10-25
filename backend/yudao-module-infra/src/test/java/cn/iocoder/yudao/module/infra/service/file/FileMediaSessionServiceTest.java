package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileMediaSessionServiceTest {
    final FileMediaSessionService service = new FileMediaSessionService();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String, String> values = mock(ValueOperations.class);
    final OAuth2TokenCommonApi tokens = mock(OAuth2TokenCommonApi.class);
    final TenantFrameworkService tenants = mock(TenantFrameworkService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final Map<String, String> store = new HashMap<>();

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "tokenApi", tokens);
        ReflectionTestUtils.setField(service, "tenantService", tenants);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "securityProperties", new SecurityProperties());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(a -> store.get(a.getArgument(0)));
        doAnswer(a -> { store.put(a.getArgument(0), a.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(a -> store.remove(a.getArgument(0)) != null);
        when(tokens.checkAccessToken("login-secret")).thenAnswer(a -> identity());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L), eq(1L))).thenReturn(1);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    OAuth2AccessTokenCheckRespDTO identity() {
        return new OAuth2AccessTokenCheckRespDTO().setUserId(7L).setUserType(2).setTenantId(1L)
                .setExpiresTime(LocalDateTime.now().plusHours(1));
    }

    MockHttpServletRequest cookieRequest() {
        var login = new MockHttpServletRequest(); login.addHeader("Authorization", "Bearer login-secret");
        var response = new MockHttpServletResponse(); service.issue(login, response);
        String header = response.getHeader("Set-Cookie");
        String reference = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        var request = new MockHttpServletRequest(); request.setCookies(new Cookie(FileMediaSessionService.COOKIE_NAME, reference));
        return request;
    }

    @Test void cookieContainsOnlyARandomReferenceAndIsReadOnlyBrowserScoped() {
        var request = new MockHttpServletRequest(); request.setSecure(true);
        request.addHeader("Authorization", "Bearer login-secret");
        var response = new MockHttpServletResponse(); service.issue(request, response);
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/admin-api/infra/file/"));
        assertTrue(cookie.contains("Max-Age=600")); assertFalse(cookie.contains("login-secret"));
        assertTrue(cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';')).matches("[A-Za-z0-9_-]{43}"));
    }

    @Test void everyCookieReadRechecksTheMainLoginActiveAccountAndTenant() {
        var request = cookieRequest(); clearInvocations(tokens, tenants, jdbc);
        assertEquals(1L, service.authorize(request)); assertEquals(1L, service.authorize(request));
        verify(tokens, times(2)).checkAccessToken("login-secret");
        verify(tenants, times(2)).validTenant(1L);
        verify(jdbc, times(2)).queryForObject(anyString(), eq(Integer.class), eq(7L), eq(1L));
    }

    @Test void revokedOrDisabledMainLoginCannotUseAnUnexpiredCookie() {
        var request = cookieRequest();
        when(tokens.checkAccessToken("login-secret")).thenThrow(exception(UNAUTHORIZED));
        assertThrows(RuntimeException.class, () -> service.authorize(request));
        doReturn(identity()).when(tokens).checkAccessToken("login-secret");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L), eq(1L))).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.authorize(request));
    }

    @Test void missingTamperedExpiredOrWrongTenantCookiesCannotAuthorizeARead() {
        assertThrows(RuntimeException.class, () -> service.authorize(new MockHttpServletRequest()));
        var tampered = new MockHttpServletRequest(); tampered.setCookies(new Cookie(FileMediaSessionService.COOKIE_NAME, "x".repeat(43)));
        assertThrows(RuntimeException.class, () -> service.authorize(tampered));
        var request = cookieRequest(); TenantContextHolder.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> service.authorize(request));
        TenantContextHolder.setTenantId(1L); store.clear();
        assertThrows(RuntimeException.class, () -> service.authorize(request));
    }

    @Test void logoutClearsReferenceAndCookieAndTheOldReferenceStopsWorking() {
        var request = cookieRequest(); var response = new MockHttpServletResponse();
        service.clear(request, response);
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertThrows(RuntimeException.class, () -> service.authorize(request));
    }
}
