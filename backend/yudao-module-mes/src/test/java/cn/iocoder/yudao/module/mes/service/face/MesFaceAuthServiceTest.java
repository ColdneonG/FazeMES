package cn.iocoder.yudao.module.mes.service.face;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic vectors only: no camera, real-person photographs or production credential rows. */
class MesFaceAuthServiceTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private MesFaceAuthService service;
    private AdminUserService users;
    private AdminAuthService auth;
    private FaceWorkerClient worker;
    private FaceChallengeStore challenges;
    private FaceTemplateCipher cipher;
    private AdminUserDO user;
    private static final String USERNAME="face-test-user";
    private static final String PASSWORD="Test-Face-Password";
    private static final String CHALLENGE="synthetic-single-use-challenge";
    private static final String IP="192.0.2.20";
    private static final String IMAGE="synthetic-frame-only";

    @BeforeEach
    void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:face_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mes_face_credential(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,user_id BIGINT,template_cipher VARCHAR(4096),enabled INT DEFAULT 1,deleted INT DEFAULT 0,creator VARCHAR(64),updater VARCHAR(64),update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,user_id))");
        users=mock(AdminUserService.class);auth=mock(AdminAuthService.class);worker=mock(FaceWorkerClient.class);challenges=mock(FaceChallengeStore.class);
        cipher=new FaceTemplateCipher(directory.resolve("master.key").toString());
        service=new MesFaceAuthService();
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"users",users);ReflectionTestUtils.setField(service,"auth",auth);ReflectionTestUtils.setField(service,"worker",worker);ReflectionTestUtils.setField(service,"cipher",cipher);ReflectionTestUtils.setField(service,"challenges",challenges);
        user=new AdminUserDO();user.setId(42L);user.setUsername(USERNAME);user.setTenantId(1L);user.setStatus(0);
        TenantContextHolder.setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(42L).setTenantId(1L),null,List.of()));
        when(users.getUser(42L)).thenReturn(user);when(users.getUserByUsername(USERNAME)).thenReturn(user);
        when(auth.authenticate(USERNAME,PASSWORD)).thenReturn(user);
        when(challenges.consume(1,USERNAME,IP,CHALLENGE)).thenReturn(true);
        when(worker.extract(IMAGE)).thenReturn(vector(0));
    }
    @AfterEach
    void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    private float[] vector(int dimension){float[] value=new float[128];value[dimension]=1;return value;}
    private void credential(long tenant,long id,boolean enabled,boolean deleted){jdbc.update("INSERT INTO mes_face_credential(tenant_id,user_id,template_cipher,enabled,deleted) VALUES(?,?,?,?,?)",tenant,id,cipher.encrypt(tenant,id,List.of(vector(0))),enabled?1:0,deleted?1:0);}
    private void rejectLogin(){assertThrows(ServiceException.class,()->service.login(USERNAME,CHALLENGE,IMAGE,IP));verify(auth,never()).loginByVerifiedFace(anyLong());}

    @Test
    void requiresExplicitConsentCurrentLoginAndPasswordReauthenticationForEnrollment(){
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,false,List.of(IMAGE)));verifyNoInteractions(worker);
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of()));
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of(IMAGE,IMAGE,IMAGE,IMAGE)));
        when(auth.authenticate(USERNAME,"bad-password")).thenThrow(new ServiceException(400,"wrong password"));
        assertThrows(ServiceException.class,()->service.enroll("bad-password",true,List.of(IMAGE)));verifyNoInteractions(worker);
        SecurityContextHolder.clearContext();assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of(IMAGE)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_credential",Integer.class));
    }
    @Test
    void cannotEnrollOrDisableCredentialForAnotherTenantOrAuthenticatedUser(){
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of(IMAGE)));
        assertThrows(ServiceException.class,()->service.disable(PASSWORD));verifyNoInteractions(worker);verify(auth,never()).authenticate(anyString(),anyString());
        TenantContextHolder.setTenantId(1L);AdminUserDO other=new AdminUserDO();other.setId(99L);other.setTenantId(1L);
        when(auth.authenticate(USERNAME,PASSWORD)).thenReturn(other);
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of(IMAGE)));verifyNoInteractions(worker);
    }
    @Test
    void enrollsOnlyEncryptedSelfTemplateAndDisableClearsCiphertextWithoutTouchingOthers(){
        credential(1,99,true,false);credential(2,42,true,false);
        service.enroll(PASSWORD,true,List.of(IMAGE));
        String sealed=jdbc.queryForObject("SELECT template_cipher FROM mes_face_credential WHERE tenant_id=1 AND user_id=42",String.class);
        assertFalse(sealed.contains(IMAGE));assertArrayEquals(vector(0),cipher.decrypt(1,42,sealed).get(0));
        service.disable(PASSWORD);
        assertEquals("",jdbc.queryForObject("SELECT template_cipher FROM mes_face_credential WHERE tenant_id=1 AND user_id=42",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT enabled FROM mes_face_credential WHERE tenant_id=1 AND user_id=42",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_credential WHERE enabled=1",Integer.class));
    }
    @Test
    void inconsistentEnrollmentImagesAreRejectedAndNeverPersisted(){
        when(worker.extract("different-synthetic-frame")).thenReturn(vector(1));
        assertThrows(ServiceException.class,()->service.enroll(PASSWORD,true,List.of(IMAGE,"different-synthetic-frame")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_credential",Integer.class));verify(auth,never()).loginByVerifiedFace(anyLong());
    }
    @Test
    void missingDisabledAndDeletedCredentialsNeverIssueTokens(){
        rejectLogin();credential(1,42,false,false);rejectLogin();
        jdbc.update("UPDATE mes_face_credential SET enabled=1,deleted=1");rejectLogin();verifyNoInteractions(worker);
    }
    @Test
    void disabledUnknownAndCrossTenantUsersNeverReachFeatureExtraction(){
        credential(1,42,true,false);user.setStatus(1);rejectLogin();
        user.setStatus(0);user.setTenantId(2L);rejectLogin();
        when(users.getUserByUsername(USERNAME)).thenReturn(null);rejectLogin();verifyNoInteractions(worker);
    }
    @Test
    void expiredOrWrongBindingChallengeStopsBeforeUserLookup(){
        credential(1,42,true,false);when(challenges.consume(1,USERNAME,IP,CHALLENGE)).thenReturn(false);
        rejectLogin();verify(users,never()).getUserByUsername(anyString());verifyNoInteractions(worker);
    }
    @Test
    void challengeCannotBeReplayedAfterSuccessfulOneToOneVerification(){
        credential(1,42,true,false);var issued=new AuthLoginRespVO();issued.setUserId(42L);
        when(auth.loginByVerifiedFace(42L)).thenReturn(issued);
        when(challenges.consume(1,USERNAME,IP,CHALLENGE)).thenReturn(true,false);
        assertSame(issued,service.login(USERNAME,CHALLENGE,IMAGE,IP));
        assertThrows(ServiceException.class,()->service.login(USERNAME,CHALLENGE,IMAGE,IP));
        verify(auth,times(1)).loginByVerifiedFace(42L);verify(worker,times(1)).extract(IMAGE);
    }
    @Test
    void correctProbeIssuesOnlyTheClaimedEnabledAccountToken(){
        credential(1,42,true,false);credential(1,99,true,false);credential(2,42,true,false);
        var expected=new AuthLoginRespVO();expected.setUserId(42L);when(auth.loginByVerifiedFace(42L)).thenReturn(expected);
        assertSame(expected,service.login(USERNAME,CHALLENGE,IMAGE,IP));
        verify(auth,only()).loginByVerifiedFace(42L);verify(users).getUserByUsername(USERNAME);
    }
    @Test
    void mismatchedNonFiniteBadDimensionAndBrokenTemplateNeverIssueTokens(){
        credential(1,42,true,false);when(worker.extract(IMAGE)).thenReturn(vector(1));rejectLogin();
        float[] nan=vector(0);nan[1]=Float.NaN;when(worker.extract(IMAGE)).thenReturn(nan);rejectLogin();
        when(worker.extract(IMAGE)).thenReturn(new float[127]);rejectLogin();
        when(worker.extract(IMAGE)).thenReturn(vector(0));
        jdbc.update("UPDATE mes_face_credential SET template_cipher=? WHERE tenant_id=1 AND user_id=42",cipher.encrypt(2,42,List.of(vector(0))));rejectLogin();
    }
    @Test
    void workerFailureAndRateLimitFailClosedWithoutIssuingTokens(){
        credential(1,42,true,false);when(worker.extract(IMAGE)).thenThrow(new IllegalStateException("unavailable"));rejectLogin();
        when(challenges.consume(1,USERNAME,IP,CHALLENGE)).thenThrow(new IllegalArgumentException("limited"));rejectLogin();
    }
    @Test
    void statusIsSelfScopedAndNeverReturnsCiphertext(){
        credential(1,99,true,false);credential(2,42,true,false);when(worker.ready()).thenReturn(true);
        assertEquals(false,service.status().get("enrolled"));credential(1,42,true,false);
        Map<String,Object> status=service.status();assertEquals(true,status.get("enrolled"));assertEquals(false,status.get("livenessSupported"));assertFalse(status.containsKey("templateCipher"));
        assertEquals(Map.of("ready",true,"comparison","1:1","livenessSupported",false),service.publicStatus());
    }
}
