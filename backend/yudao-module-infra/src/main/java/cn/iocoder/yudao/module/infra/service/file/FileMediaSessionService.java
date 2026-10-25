package cn.iocoder.yudao.module.infra.service.file;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * Read-only browser media authentication. URLs remain stable and contain no login credential.
 * The short-lived HttpOnly cookie is an opaque Redis reference, never an access/refresh token.
 * Every download rechecks the original login, account and tenant, so logout/force-logout is immediate.
 */
@Service
public class FileMediaSessionService {
    static final String COOKIE_NAME = "FazeFileMedia";
    static final String COOKIE_PATH = "/admin-api/infra/file/";
    static final int MAX_SECONDS = 600;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private OAuth2TokenCommonApi tokenApi;
    @Resource private TenantFrameworkService tenantService;
    @Resource private JdbcTemplate jdbcTemplate;
    @Resource private SecurityProperties securityProperties;

    @Data
    public static class MediaSession {
        private String accessToken;
        private Long tenantId;
        private Long userId;
    }

    public Instant issue(HttpServletRequest request, HttpServletResponse response) {
        String header = request.getHeader(securityProperties.getTokenHeader());
        if (header == null || !header.startsWith("Bearer ")) throw exception(UNAUTHORIZED);
        String token = header.substring(7).trim();
        var identity = activeIdentity(token);
        if (!Objects.equals(TenantContextHolder.getTenantId(), identity.getTenantId())) throw exception(FORBIDDEN);
        long seconds = Math.min(MAX_SECONDS, Duration.between(LocalDateTime.now(), identity.getExpiresTime()).getSeconds());
        if (seconds <= 0) throw exception(UNAUTHORIZED);
        // Keep the previous reference until its short TTL expires: an image request already in flight
        // may still carry it. Both references recheck the same main login on every read.
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String reference = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        MediaSession session = new MediaSession().setAccessToken(token).setTenantId(identity.getTenantId())
                .setUserId(identity.getUserId());
        stringRedisTemplate.opsForValue().set(key(reference), JsonUtils.toJsonString(session), Duration.ofSeconds(seconds));
        setCookie(response, reference, seconds, request.isSecure());
        return Instant.now().plusSeconds(seconds);
    }

    /** Only FileController's GET content action calls this method; it grants no mutation authority. */
    public Long authorize(HttpServletRequest request) {
        String header = request.getHeader(securityProperties.getTokenHeader());
        if (header != null && header.startsWith("Bearer ")) {
            var identity = activeIdentity(header.substring(7).trim());
            Long suppliedTenant = TenantContextHolder.getTenantId();
            if (suppliedTenant != null && !Objects.equals(suppliedTenant, identity.getTenantId())) throw exception(FORBIDDEN);
            return identity.getTenantId();
        }
        String reference = reference(request);
        if (reference == null) throw exception(UNAUTHORIZED);
        String value = stringRedisTemplate.opsForValue().get(key(reference));
        if (value == null) throw exception(UNAUTHORIZED);
        MediaSession session;
        try { session = JsonUtils.parseObject(value, MediaSession.class); }
        catch (RuntimeException ex) { throw exception(UNAUTHORIZED); }
        if (session == null || session.getAccessToken() == null) throw exception(UNAUTHORIZED);
        var identity = activeIdentity(session.getAccessToken());
        if (!Objects.equals(identity.getTenantId(), session.getTenantId())
                || !Objects.equals(identity.getUserId(), session.getUserId())) throw exception(UNAUTHORIZED);
        Long suppliedTenant = TenantContextHolder.getTenantId();
        if (suppliedTenant != null && !Objects.equals(suppliedTenant, identity.getTenantId())) throw exception(FORBIDDEN);
        return identity.getTenantId();
    }

    private OAuth2AccessTokenCheckRespDTO activeIdentity(String token) {
        var identity = tokenApi.checkAccessToken(token);
        if (identity == null || !Objects.equals(identity.getUserType(), 2) || identity.getTenantId() == null
                || identity.getUserId() == null || identity.getExpiresTime() == null
                || !identity.getExpiresTime().isAfter(LocalDateTime.now())) throw exception(UNAUTHORIZED);
        tenantService.validTenant(identity.getTenantId());
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_users WHERE id=? AND tenant_id=? AND status=0 AND deleted=0",
                Integer.class, identity.getUserId(), identity.getTenantId());
        if (active == null || active != 1) throw exception(UNAUTHORIZED);
        return identity;
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        String old = reference(request);
        if (old != null) stringRedisTemplate.delete(key(old));
        setCookie(response, "", 0, request.isSecure());
    }

    private static String reference(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}")) return cookie.getValue();
        }
        return null;
    }

    private static String key(String reference) { return "infra:file-media:" + DigestUtil.sha256Hex(reference); }

    private static void setCookie(HttpServletResponse response, String value, long seconds, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true).secure(secure).sameSite("Strict").path(COOKIE_PATH).maxAge(seconds).build().toString());
    }
}
