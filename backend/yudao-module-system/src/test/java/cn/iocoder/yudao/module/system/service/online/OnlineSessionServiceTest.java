package cn.iocoder.yudao.module.system.service.online;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.system.controller.admin.online.vo.OnlineSessionPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.*;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.*;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.*;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Import({OnlineSessionService.class,OAuth2TokenServiceImpl.class})
class OnlineSessionServiceTest extends BaseDbUnitTest {
    @MockitoSpyBean OnlineSessionService service;
    @Resource OAuth2TokenServiceImpl tokenService;
    @Resource OAuth2AccessTokenMapper accessMapper;
    @Resource OAuth2RefreshTokenMapper refreshMapper;
    @MockitoBean OAuth2ClientService clientService;
    @MockitoBean AdminUserService userService;
    @MockitoBean OAuth2AccessTokenRedisDAO tokenRedisDAO;
    @AfterEach void clearTenant() {TenantContextHolder.clear();}
    OAuth2AccessTokenDO fixture(long tenant) {
        TenantContextHolder.setTenantId(tenant);
        var refresh=new OAuth2RefreshTokenDO().setRefreshToken("fixture-refresh-"+tenant).setUserId(7L).setUserType(2)
            .setClientId("test-client").setScopes(List.of()).setExpiresTime(LocalDateTime.now().plusHours(1));
        refresh.setTenantId(tenant);refreshMapper.insert(refresh);
        var access=new OAuth2AccessTokenDO().setAccessToken("fixture-access-"+tenant).setRefreshToken(refresh.getRefreshToken())
            .setUserId(7L).setUserType(2).setUserInfo(Map.of()).setClientId("test-client").setScopes(List.of())
            .setExpiresTime(LocalDateTime.now().plusMinutes(5));access.setTenantId(tenant);accessMapper.insert(access);return access;
    }
    OAuth2ClientDO client() {return new OAuth2ClientDO().setClientId("test-client").setAccessTokenValiditySeconds(300).setRefreshTokenValiditySeconds(3600);}
    @Test void anotherTenantCannotListOrRevokeSession() {
        var foreign=fixture(2);TenantContextHolder.setTenantId(1L);
        assertTrue(service.page(new OnlineSessionPageReqVO()).getList().isEmpty());
        assertFalse(service.revoke(foreign.getId()));
        assertNotNull(accessMapper.selectById(foreign.getId()));
        verifyNoInteractions(tokenRedisDAO);
    }
    @Test void sessionResponseNeverContainsCredentials() {
        var access=fixture(1);
        when(userService.getUserMap(anyCollection())).thenReturn(Map.of(7L,new AdminUserDO().setUsername("demo").setNickname("Demo")));
        var page=service.page(new OnlineSessionPageReqVO());
        assertEquals(1,page.getTotal());
        String json=JsonUtils.toJsonString(page);
        assertFalse(json.contains(access.getAccessToken()));assertFalse(json.contains(access.getRefreshToken()));
        assertFalse(json.contains("accessToken"));assertFalse(json.contains("refreshToken"));
    }
    @Test void revokeWaitsForInFlightRefreshThenRevokesNewAccessToken() throws Exception {
        var original=fixture(1);
        CountDownLatch refreshLocked=new CountDownLatch(1),allowRefresh=new CountDownLatch(1),snapshotRead=new CountDownLatch(1);
        when(clientService.validOAuthClientFromCache("test-client")).thenAnswer(a->{refreshLocked.countDown();assertTrue(allowRefresh.await(10,TimeUnit.SECONDS));return client();});
        when(userService.getUser(7L)).thenReturn(new AdminUserDO().setNickname("Demo").setDeptId(1L));
        doAnswer(a->{Object found=a.callRealMethod();snapshotRead.countDown();return found;}).when(service).findSession(original.getId(),1L);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<OAuth2AccessTokenDO> refreshing=executor.submit(()->{TenantContextHolder.setTenantId(1L);try{return tokenService.refreshAccessToken(original.getRefreshToken(),"test-client");}finally{TenantContextHolder.clear();}});
            assertTrue(refreshLocked.await(10,TimeUnit.SECONDS));
            Future<Boolean> revoking=executor.submit(()->{TenantContextHolder.setTenantId(1L);try{return service.revoke(original.getId());}finally{TenantContextHolder.clear();}});
            assertTrue(snapshotRead.await(10,TimeUnit.SECONDS));allowRefresh.countDown();
            var issued=refreshing.get(15,TimeUnit.SECONDS);assertTrue(revoking.get(15,TimeUnit.SECONDS));
            assertNull(accessMapper.selectByAccessToken(issued.getAccessToken()));assertNull(refreshMapper.selectByRefreshToken(original.getRefreshToken()));
            verify(tokenRedisDAO).deleteList(argThat(values->values.contains(issued.getAccessToken())));
        } finally {allowRefresh.countDown();executor.shutdownNow();}
    }
    @Test void refreshWaitingForRevokeCannotMintAnotherSession() throws Exception {
        var original=fixture(1);
        CountDownLatch revokedRows=new CountDownLatch(1),allowCommit=new CountDownLatch(1);
        doAnswer(a->{revokedRows.countDown();assertTrue(allowCommit.await(10,TimeUnit.SECONDS));return null;}).when(tokenRedisDAO).deleteList(anyCollection());
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> revoking=executor.submit(()->{TenantContextHolder.setTenantId(1L);try{return service.revoke(original.getId());}finally{TenantContextHolder.clear();}});
            assertTrue(revokedRows.await(10,TimeUnit.SECONDS));
            Future<?> refreshing=executor.submit(()->{TenantContextHolder.setTenantId(1L);try{return tokenService.refreshAccessToken(original.getRefreshToken(),"test-client");}finally{TenantContextHolder.clear();}});
            allowCommit.countDown();assertTrue(revoking.get(15,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->refreshing.get(15,TimeUnit.SECONDS));
            assertTrue(accessMapper.selectListByUserIdAndUserType(7L,2).isEmpty());
            verifyNoInteractions(clientService);
        } finally {allowCommit.countDown();executor.shutdownNow();}
    }
}
