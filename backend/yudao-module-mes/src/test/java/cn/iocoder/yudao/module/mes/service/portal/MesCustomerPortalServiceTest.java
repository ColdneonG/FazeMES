package cn.iocoder.yudao.module.mes.service.portal;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static cn.iocoder.yudao.module.mes.service.portal.MesCustomerPortalService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL and transaction proxy: isolation assertions do not stub repositories or token validation. */
class MesCustomerPortalServiceTest {
    private JdbcTemplate jdbc;
    private MesCustomerPortalService service;
    private long account;
    private static final String PASSWORD = "Test-Portal-9842!";

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:portal_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/customer_portal.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        ProxyFactory proxy = new ProxyFactory(new MesCustomerPortalService(jdbc));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (MesCustomerPortalService) proxy.getProxy();
        jdbc.update("INSERT INTO system_tenant VALUES(1,0,0,?),(2,0,0,?)", LocalDateTime.now().plusYears(1), LocalDateTime.now().plusYears(1));
        jdbc.update("INSERT INTO mes_md_client(id,tenant_id,code,name,address,credit_code) VALUES(11,1,'C11','客户一','敏感地址一','敏感信用码'),(12,1,'C12','客户二','敏感地址二','隐藏信用码'),(21,2,'C21','其他租户客户','另一租户地址','另一租户信用码')");
        jdbc.update("INSERT INTO mes_md_unit_measure(id,tenant_id,name) VALUES(1,1,'件'),(2,2,'秘密单位')");
        jdbc.update("INSERT INTO mes_md_item(id,tenant_id,code,name,specification,unit_measure_id,remark) VALUES(101,1,'P101','公开产品','规格A',1,'内部成本备注'),(102,1,'P102','未公开物料','规格B',1,'内部秘密'),(201,2,'P201','另一个租户产品','秘密型号',2,'另一个租户秘密')");
        TenantContextHolder.setTenantId(1L);
        account = service.saveAccount(accountSave(null,11L,"client-one",true,""));
    }
    @AfterEach
    void cleanup() { TenantContextHolder.clear(); if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }
    private AccountSave accountSave(Long id, Long client, String name, boolean enabled, String password) {
        return new AccountSave(id,client,name,"客户联系人",id == null ? PASSWORD : password,enabled,null);
    }
    private String login() { return (String) service.login(new LoginRequest(1L,"client-one",PASSWORD),"127.0.0.1").get("token"); }
    private void publish() { service.saveCatalog(new CatalogSave(101L,"公开规格说明",true)); }
    private OrderRequest request(String key) { return new OrderRequest(key,LocalDate.now().plusDays(10),"客户公开备注",List.of(new RequestLine(101L,new BigDecimal("3.250000")))); }
    private String key() { return UUID.randomUUID().toString(); }
    private void order(long id,long tenant,long client,int status,long product) {
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,client_id,product_id,code,name,quantity,quantity_produced,request_date,status,remark,vendor_id) VALUES(?,?,?,?,?,?,10,4,?,?,?,999)",id,tenant,client,product,"WO-"+id,"订单"+id,LocalDateTime.now().plusDays(3),status,"不应返回的内部备注");
    }
    private static void unauthorized(Runnable action) { assertEquals(401,assertThrows(ServiceException.class,action::run).getCode()); }

    @Test
    void storesOnlyPasswordHashAndTokenDigestAndReturnsMinimalIdentity() {
        String token = login();
        assertEquals(43,token.length());
        String encoded = jdbc.queryForObject("SELECT password_hash FROM mes_portal_account WHERE id=?",String.class,account);
        assertNotEquals(PASSWORD,encoded); assertTrue(encoded.startsWith("$2"));
        String digest = jdbc.queryForObject("SELECT token_hash FROM mes_portal_session",String.class);
        assertEquals(64,digest.length()); assertNotEquals(token,digest);
        assertEquals(Map.of("displayName","客户联系人","clientName","客户一"),service.me(token));
        assertFalse(service.accounts().toString().contains(encoded));
        assertFalse(service.me(token).toString().contains("敏感"));
    }

    @Test
    void enforcesCustomerAndTenantAndSafeOrderProjectionEvenWhenTenantInterceptionIsIgnored() {
        order(1,1,11,1,101); order(2,1,11,0,101); order(3,1,12,1,101);
        order(4,2,11,1,201); order(5,1,11,2,201); order(6,1,11,3,101);
        order(7,1,11,1,101); jdbc.update("UPDATE mes_pro_work_order SET deleted=1 WHERE id=7");
        String token = login();
        TenantContextHolder.setTenantId(2L); TenantContextHolder.setIgnore(true);
        var orders = service.orders(token);
        assertEquals(Set.of(1L,5L,6L),orders.stream().map(row -> ((Number)row.get("id")).longValue()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("",orders.stream().filter(row -> ((Number)row.get("id")).longValue()==5).findFirst().orElseThrow().get("productName"));
        assertTrue(orders.get(0).get("requestDate") instanceof String);
        String output = orders.toString();
        for(String secret:List.of("内部备注","vendorId","clientId","秘密型号","另一个租户产品","敏感地址")) assertFalse(output.contains(secret),secret);
    }

    @Test
    void catalogIsExplicitlyPublishedAndJoinsCannotLeakForeignTenantMetadata() {
        String token = login(); assertTrue(service.catalog(token).isEmpty());
        publish();
        jdbc.update("INSERT INTO mes_portal_catalog(tenant_id,product_id,description,enabled) VALUES(1,201,'坏关联',1),(2,102,'其他租户关联',1)");
        var catalog = service.catalog(token); assertEquals(1,catalog.size());
        assertEquals(101L,((Number)catalog.get(0).get("productId")).longValue());
        assertFalse(catalog.toString().contains("内部成本")); assertFalse(catalog.toString().contains("秘密"));
        service.saveCatalog(new CatalogSave(101L,"下架",false)); assertTrue(service.catalog(token).isEmpty());
        assertThrows(ServiceException.class,() -> service.saveCatalog(new CatalogSave(201L,"越权",true)));
    }

    @Test
    void checkoutIsIdempotentAndRequestLinesAndHistoryAreCustomerScoped() {
        publish(); String token = login(); String requestKey = key();
        var created = service.submit(token,request(requestKey));
        assertEquals(created,service.submit(token,request(requestKey)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request",Integer.class));
        jdbc.update("INSERT INTO mes_portal_request_line(tenant_id,request_id,product_id,product_code,product_name,quantity) VALUES(2,?,201,'FOREIGN','跨租户行',88)",created.get("id"));
        service.saveAccount(accountSave(null,12L,"client-two",true,""));
        String other = (String)service.login(new LoginRequest(1L,"client-two",PASSWORD),"127.0.0.2").get("token");
        assertTrue(service.requests(other).isEmpty());
        assertFalse(service.requests(token).toString().contains("跨租户行"));
        assertEquals(1,((List<?>)service.requests(token).get(0).get("items")).size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_work_order",Integer.class));
        TenantContextHolder.setTenantId(2L); assertTrue(service.adminRequests().isEmpty());
    }

    @Test
    void checkoutRejectsUnpublishedForeignDuplicateAndInvalidQuantitiesWithoutWriting() {
        publish(); String token = login();
        List<List<RequestLine>> invalid = List.of(List.of(new RequestLine(201L,BigDecimal.ONE)),List.of(new RequestLine(102L,BigDecimal.ONE)),List.of(new RequestLine(101L,BigDecimal.ZERO)),List.of(new RequestLine(101L,new BigDecimal("0.0000001"))),List.of(new RequestLine(101L,new BigDecimal("1000001"))),List.of(new RequestLine(101L,BigDecimal.ONE),new RequestLine(101L,BigDecimal.ONE)));
        for (var items : invalid) assertThrows(ServiceException.class,() -> service.submit(token,new OrderRequest(key(),LocalDate.now().plusDays(2),"",items)));
        assertThrows(ServiceException.class,() -> service.submit(token,new OrderRequest(key(),LocalDate.now().minusDays(1),"",List.of(new RequestLine(101L,BigDecimal.ONE)))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request",Integer.class));
    }

    @Test
    void checkoutRollsBackHeaderWhenDatabaseRejectsALine() {
        publish(); String token = login();
        jdbc.update("UPDATE mes_md_item SET code=NULL WHERE id=101");
        assertThrows(DataIntegrityViolationException.class,() -> service.submit(token,request(key())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request_line",Integer.class));
    }

    @Test
    void concurrentRetryCommitsOneHeaderAndOneSetOfLines() throws Exception {
        publish(); String token = login(); OrderRequest body = request(key());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.submit(token,body));
            var second = pool.submit(() -> service.submit(token,body));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_portal_request_line",Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void reviewsAreScopedSingleTransitionAndClientVisible() {
        publish(); String token = login(); long requestId = ((Number)service.submit(token,request(key())).get("id")).longValue();
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class,() -> service.review(new ReviewRequest(requestId,"ACCEPTED","越权"),7L));
        TenantContextHolder.setTenantId(1L);
        service.review(new ReviewRequest(requestId,"ACCEPTED","已联系确认交期"),8L);
        var visible = service.requests(token).get(0); assertEquals("ACCEPTED",visible.get("status")); assertEquals("已联系确认交期",visible.get("reviewNote")); assertFalse(visible.containsKey("reviewerId"));
        assertThrows(ServiceException.class,() -> service.review(new ReviewRequest(requestId,"REJECTED","再次处理"),8L));
    }

    @Test
    void logoutExpiryDisablePasswordResetAndRebindingRevokeSessions() {
        String logout = login(); service.logout(logout); unauthorized(() -> service.me(logout));
        String expired = login(); jdbc.update("UPDATE mes_portal_session SET expires_at=?",LocalDateTime.now().minusSeconds(1)); unauthorized(() -> service.me(expired));
        String disabled = login(); service.saveAccount(accountSave(account,11L,"client-one",false,"")); unauthorized(() -> service.me(disabled)); unauthorized(this::login);
        service.saveAccount(accountSave(account,11L,"client-one",true,""));
        String oldPassword = login(); service.saveAccount(accountSave(account,11L,"client-one",true,"New-Password-936!")); unauthorized(() -> service.me(oldPassword)); unauthorized(this::login);
        String rebound = (String)service.login(new LoginRequest(1L,"client-one","New-Password-936!"),"127.0.0.3").get("token");
        service.saveAccount(accountSave(account,12L,"client-one",true,"")); unauthorized(() -> service.me(rebound));
    }

    @Test
    void deletedOrDisabledCustomerAndExpiredTenantImmediatelyInvalidateTokens() {
        String token = login();
        jdbc.update("UPDATE mes_md_client SET deleted=1 WHERE id=11"); unauthorized(() -> service.me(token));
        jdbc.update("UPDATE mes_md_client SET deleted=0,status=1 WHERE id=11"); unauthorized(() -> service.me(token));
        jdbc.update("UPDATE mes_md_client SET status=0 WHERE id=11");
        jdbc.update("UPDATE system_tenant SET expire_time=? WHERE id=1",LocalDateTime.now().minusSeconds(1)); unauthorized(() -> service.me(token)); unauthorized(this::login);
        jdbc.update("UPDATE system_tenant SET expire_time=?,status=1 WHERE id=1",LocalDateTime.now().plusDays(1)); unauthorized(() -> service.me(token));
    }

    @Test
    void persistentAccountLockAndUnknownAccountIpRateLimitAreEnforced() {
        for (int i=0;i<5;i++) unauthorized(() -> service.login(new LoginRequest(1L,"client-one","incorrect-password"),"192.0.2.1"));
        assertEquals(5,jdbc.queryForObject("SELECT failed_attempts FROM mes_portal_account WHERE id=?",Integer.class,account));
        unauthorized(this::login);
        jdbc.update("UPDATE mes_portal_account SET locked_until=? WHERE id=?",LocalDateTime.now().minusSeconds(1),account);
        assertNotNull(login()); assertEquals(0,jdbc.queryForObject("SELECT failed_attempts FROM mes_portal_account WHERE id=?",Integer.class,account));
        for(int i=0;i<30;i++) unauthorized(() -> service.login(new LoginRequest(1L,"unknown-account",PASSWORD),"192.0.2.30"));
        assertEquals(429,assertThrows(ServiceException.class,() -> service.login(new LoginRequest(1L,"unknown-account",PASSWORD),"192.0.2.30")).getCode());
    }

    @Test
    void adminMutationsRejectCrossTenantIdsAndMissingTenantFailsClosed() {
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class,() -> service.saveAccount(accountSave(account,21L,"client-one",true,"")));
        assertThrows(ServiceException.class,() -> service.saveAccount(accountSave(null,11L,"bad-binding",true,"")));
        assertTrue(service.accounts().isEmpty());
        TenantContextHolder.clear(); assertThrows(Exception.class,service::accounts); assertThrows(Exception.class,service::adminRequests);
        unauthorized(() -> service.me(null)); unauthorized(() -> service.orders("forged-token"));
    }
}
