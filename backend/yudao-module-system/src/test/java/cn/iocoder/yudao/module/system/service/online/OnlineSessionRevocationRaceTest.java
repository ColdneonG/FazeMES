package cn.iocoder.yudao.module.system.service.online;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbAndRedisUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.*;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.*;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.*;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static cn.iocoder.yudao.module.system.dal.redis.RedisKeyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Redis/H2 regression: late cache writes cannot undo an explicitly revoked login session. */
@Import({OnlineSessionService.class, OAuth2TokenServiceImpl.class, OAuth2AccessTokenRedisDAO.class})
class OnlineSessionRevocationRaceTest extends BaseDbAndRedisUnitTest {
    @Resource OnlineSessionService service;
    @Resource OAuth2TokenServiceImpl tokenService;
    @Resource OAuth2AccessTokenMapper accessMapper;
    @Resource OAuth2RefreshTokenMapper refreshMapper;
    @Resource StringRedisTemplate redis;
    @MockitoSpyBean OAuth2AccessTokenRedisDAO cache;
    @MockitoBean OAuth2ClientService clientService;
    @MockitoBean AdminUserService userService;
    final Set<String> credentials = new HashSet<>();

    @BeforeEach void tenant() { TenantContextHolder.setTenantId(1L); }
    @AfterEach void cleanup() {
        for (String token : credentials) {
            redis.delete(String.format(OAUTH2_ACCESS_TOKEN, token));
            redis.delete(String.format(OAUTH2_REVOKED_TOKEN, DigestUtil.sha256Hex(token)));
        }
        TenantContextHolder.clear();
    }
    String credential() {
        String token = "revocation-fixture-" + UUID.randomUUID();
        credentials.add(token);
        return token;
    }
    OAuth2AccessTokenDO fixture(boolean withRefresh) {
        String refresh = withRefresh ? credential() : ""; // Legacy blank value; schema requires NOT NULL.
        if (withRefresh) {
            var row = new OAuth2RefreshTokenDO().setRefreshToken(refresh).setUserId(7L).setUserType(2)
                    .setClientId("revocation-test").setScopes(List.of()).setExpiresTime(LocalDateTime.now().plusHours(1));
            row.setTenantId(1L);
            refreshMapper.insert(row);
        }
        var access = cachedCredential(credential(), refresh, LocalDateTime.now().plusMinutes(5));
        accessMapper.insert(access);
        return access;
    }
    OAuth2AccessTokenDO cachedCredential(String access, String refresh, LocalDateTime expiry) {
        var row = new OAuth2AccessTokenDO().setAccessToken(access).setRefreshToken(refresh).setUserId(7L).setUserType(2)
                .setClientId("revocation-test").setScopes(List.of()).setUserInfo(Map.of()).setExpiresTime(expiry);
        row.setTenantId(1L);
        return row;
    }
    void unauthorized(String token) {
        var error = assertThrows(ServiceException.class, () -> tokenService.checkAccessToken(token));
        assertEquals(401, error.getCode());
    }
    void deniedFuture(Future<?> validation) throws Exception {
        var failure = assertThrows(ExecutionException.class, () -> validation.get(15, TimeUnit.SECONDS));
        assertInstanceOf(ServiceException.class, failure.getCause());
        assertEquals(401, ((ServiceException) failure.getCause()).getCode());
    }
    Future<?> validating(ExecutorService executor, String token) {
        return executor.submit(() -> {
            TenantContextHolder.setTenantId(1L);
            try { return tokenService.checkAccessToken(token); }
            finally { TenantContextHolder.clear(); }
        });
    }

    @Test void databaseReadThatRefillsCacheAfterRevocationCannotAuthenticate() throws Exception {
        var access = fixture(true);
        CountDownLatch read = new CountDownLatch(1), refill = new CountDownLatch(1);
        doAnswer(invocation -> {
            read.countDown();
            assertTrue(refill.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(cache).set(argThat(row -> row.getAccessToken().equals(access.getAccessToken())));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> validation = validating(executor, access.getAccessToken());
            assertTrue(read.await(10, TimeUnit.SECONDS));
            assertTrue(service.revoke(access.getId())); // Returns after the deletion transaction commits.
            refill.countDown();
            deniedFuture(validation);
            assertNotNull(cache.get(access.getAccessToken()), "The late cache refill really occurred");
            unauthorized(access.getAccessToken());
            // Old access tokens no longer in DB and the supported refresh-as-access alias share the marker.
            String older = credential();
            cache.set(cachedCredential(older, access.getRefreshToken(), access.getExpiresTime()));
            unauthorized(older);
            cache.set(cachedCredential(access.getRefreshToken(), access.getRefreshToken(), LocalDateTime.now().plusMinutes(30)));
            unauthorized(access.getRefreshToken());
        } finally { refill.countDown(); executor.shutdownNow(); }
    }

    @Test void cachedObjectReadBeforeRevocationIsRejectedBeforeReturningIt() throws Exception {
        var access = fixture(true);
        cache.set(access);
        CountDownLatch read = new CountDownLatch(1), returned = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object cached = invocation.callRealMethod();
            read.countDown();
            assertTrue(returned.await(10, TimeUnit.SECONDS));
            return cached;
        }).when(cache).get(access.getAccessToken());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> validation = validating(executor, access.getAccessToken());
            assertTrue(read.await(10, TimeUnit.SECONDS));
            assertTrue(service.revoke(access.getId()));
            returned.countDown();
            deniedFuture(validation);
        } finally { returned.countDown(); executor.shutdownNow(); }
    }

    @Test void legacySessionWithoutRefreshRevokesOnlyThatAccessCredential() {
        var revoked = fixture(false);
        var sibling = fixture(false);
        assertTrue(service.revoke(revoked.getId()));
        cache.set(revoked); // Simulate an old object being put back after deletion.
        unauthorized(revoked.getAccessToken());
        assertNotNull(tokenService.checkAccessToken(sibling.getAccessToken()));
        assertNotNull(accessMapper.selectById(sibling.getId()));
    }

    @Test void independentLoginChainCanStillValidateAndRefresh() {
        var revoked = fixture(true);
        var sibling = fixture(true);
        assertTrue(service.revoke(revoked.getId()));
        assertNotNull(tokenService.checkAccessToken(sibling.getAccessToken()));
        when(clientService.validOAuthClientFromCache("revocation-test")).thenReturn(new OAuth2ClientDO()
                .setClientId("revocation-test").setAccessTokenValiditySeconds(300).setRefreshTokenValiditySeconds(3600));
        when(userService.getUser(7L)).thenReturn(new AdminUserDO().setNickname("Fixture").setDeptId(1L));
        var refreshed = tokenService.refreshAccessToken(sibling.getRefreshToken(), "revocation-test");
        credentials.add(refreshed.getAccessToken());
        assertNotNull(tokenService.checkAccessToken(refreshed.getAccessToken()));
        assertEquals(401, assertThrows(ServiceException.class,
                () -> tokenService.refreshAccessToken(revoked.getRefreshToken(), "revocation-test")).getCode());
    }

    @Test void markerUsesHashedKeyCoversExpiryAndCannotBeShortenedByRetry() {
        String token = credential();
        cache.markRevoked(token, LocalDateTime.now().plusMinutes(4));
        String key = String.format(OAUTH2_REVOKED_TOKEN, DigestUtil.sha256Hex(token));
        long before = Objects.requireNonNull(redis.getExpire(key));
        assertTrue(before >= 299 && before <= 301);
        assertFalse(key.contains(token));
        cache.markRevoked(token, LocalDateTime.now().plusSeconds(1));
        long after = Objects.requireNonNull(redis.getExpire(key));
        assertTrue(after >= before - 2, "An admin retry must not shorten an existing chain revocation");
        assertTrue(cache.isRevoked(token, null));
    }

    @Test void failedMarkerWriteCannotReportSuccessOrRemoveDatabaseRows() {
        var access = fixture(true);
        doThrow(new IllegalStateException("Revocation store unavailable")).when(cache).markRevoked(eq(access.getRefreshToken()), any());
        assertThrows(IllegalStateException.class, () -> service.revoke(access.getId()));
        assertNotNull(accessMapper.selectById(access.getId()));
        assertNotNull(refreshMapper.selectByRefreshToken(access.getRefreshToken()));
        verify(cache, never()).deleteList(anyCollection());
    }

    @Test void laterTransactionFailureKeepsExplicitRevocationFailClosed() {
        var access = fixture(true);
        doThrow(new IllegalStateException("Cache cleanup interrupted")).when(cache).deleteList(anyCollection());
        assertThrows(IllegalStateException.class, () -> service.revoke(access.getId()));
        assertNotNull(accessMapper.selectById(access.getId()), "Database deletion rolled back");
        assertNotNull(refreshMapper.selectByRefreshToken(access.getRefreshToken()));
        unauthorized(access.getAccessToken());
        assertEquals(401, assertThrows(ServiceException.class,
                () -> tokenService.refreshAccessToken(access.getRefreshToken(), "revocation-test")).getCode());
        verifyNoInteractions(clientService);
    }

    @Test void unavailableMarkerReadNeverAuthenticatesAnOtherwiseValidCredential() {
        var access = fixture(true);
        doThrow(new IllegalStateException("Revocation status unavailable")).when(cache).isRevoked(eq(access.getAccessToken()), any());
        assertThrows(IllegalStateException.class, () -> tokenService.checkAccessToken(access.getAccessToken()));
    }
}
