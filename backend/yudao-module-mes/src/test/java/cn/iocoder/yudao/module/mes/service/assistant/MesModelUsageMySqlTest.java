package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.vo.MesModelUsageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB, actual production services and bounded local provider; never the application database. */
@EnabledIfEnvironmentVariable(named="MES_MODEL_USAGE_MYSQL_URL",
        matches="jdbc:mysql://127\\.0\\.0\\.1:59685/model_usage_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesModelUsageMySqlTest {
    @TempDir Path temporary;
    private static final Set<String> OWNED_TABLES=Set.of(
            "mes_assistant_knowledge_base","mes_assistant_document","mes_assistant_chunk",
            "mes_assistant_session","mes_assistant_message","mes_assistant_model_config",
            "mes_model_usage_policy","mes_model_usage_bucket","mes_model_usage_log","mes_model_usage_event",
            "mes_insight_jev_config","mes_insight_jev_triage","mes_insight_incident_assignment");
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ObjectMapper json;
    private MesAssistantSecretStore secrets;
    private MesAssistantModelGateway gateway;
    private MesAssistantService assistant;
    private MesModelUsageService usage;
    private MesModelUsageService secondUsage;
    private MutableClock clock;
    private String schema;
    private boolean ownsTables;
    private Path root;

    @BeforeEach void nativeDatabase() throws Exception {
        String url=System.getenv("MES_MODEL_USAGE_MYSQL_URL");
        URI uri=URI.create(url.substring(5));schema=uri.getPath().substring(1);
        assertEquals("127.0.0.1",uri.getHost());assertEquals(59685,uri.getPort());
        assertTrue(schema.matches("model_usage_it_[A-Za-z0-9_]+"));
        source=new DriverManagerDataSource(url,System.getenv().getOrDefault("MES_MODEL_USAGE_MYSQL_USER","root"),
                System.getenv().getOrDefault("MES_MODEL_USAGE_MYSQL_PASSWORD",""));
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);json=new ObjectMapper();
        assertOwnedDatabase();
        assertTrue(tableNames().isEmpty(),"Use an explicitly EMPTY owned disposable schema");ownsTables=true;
        root=repository();
        // Load the production DDL, excluding unrelated menu authorization mutations.
        executeCreates(root.resolve("database/migrations/20261018_assistant_rag.sql"));
        executeCreates(root.resolve("database/migrations/20261020_jev_incident_triage.sql"));
        jdbc.execute("ALTER TABLE mes_assistant_model_config ADD COLUMN context_tokens INT NOT NULL DEFAULT 8192");
        new ResourceDatabasePopulator(new FileSystemResource(root.resolve("database/migrations/20261020_model_usage_budget.sql"))).execute(source);
        clock=new MutableClock(Instant.parse("2024-02-29T15:59:59Z"));
        usage=new MesModelUsageService(jdbc,json,manager,clock);
        var secondSource=new DriverManagerDataSource(url,System.getenv().getOrDefault("MES_MODEL_USAGE_MYSQL_USER","root"),
                System.getenv().getOrDefault("MES_MODEL_USAGE_MYSQL_PASSWORD",""));
        secondUsage=new MesModelUsageService(new JdbcTemplate(secondSource),json,new DataSourceTransactionManager(secondSource),clock);
        secrets=new MesAssistantSecretStore(new MockEnvironment().withProperty("mes.assistant.master-key-file",temporary.resolve("private/master.key").toString()));
        gateway=new MesAssistantModelGateway(jdbc,json,secrets,usage,"http://127.0.0.1:1",10000);
        var target=new MesAssistantService(jdbc,json,new MesAssistantDocumentParser(),new MesAssistantRetriever(),gateway);
        // The actual annotation interceptor is essential: direct construction would
        // hide the old send() transaction and falsely pass the no-lock assertion.
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        assistant=(MesAssistantService)factory.getProxy();
        login(1,10);
        policy(generous(),generous());
    }
    private void executeCreates(Path migration) throws Exception {
        var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS ([a-z_]+) \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(Files.readString(migration));
        while(matcher.find()) {assertTrue(OWNED_TABLES.contains(matcher.group(1)));jdbc.execute(matcher.group());}
    }
    private static Path repository() {
        for(Path path=Path.of("").toAbsolutePath();path!=null;path=path.getParent())
            if(Files.isRegularFile(path.resolve("database/migrations/20261018_assistant_rag.sql")))return path;
        throw new IllegalStateException("Cannot locate maintained production migrations");
    }
    private List<String> tableNames(){return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",String.class,schema);}
    private void assertOwnedDatabase(){assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));assertEquals(59685,jdbc.queryForObject("SELECT @@port",Integer.class));}
    @AfterEach void cleanup() {
        clearLogin();
        if(ownsTables){assertOwnedDatabase();for(String table:tableNames()){assertTrue(OWNED_TABLES.contains(table),"Refuse to drop an unexpected table");jdbc.execute("DROP TABLE "+table);}}
    }
    private static void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    private static void clearLogin(){SecurityContextHolder.clearContext();TenantContextHolder.clear();}
    private TransactionTemplate transaction(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;}
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial){instant=new AtomicReference<>(initial);}
        void advance(Duration duration){instant.updateAndGet(value->value.plus(duration));}
        @Override public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant.get();}
    }
    private MesModelUsageVO.Limits generous(){return new MesModelUsageVO.Limits("1000","1000","1000000","1000000",null,null,100,1000);}
    private void policy(MesModelUsageVO.Limits tenant,MesModelUsageVO.Limits user){
        Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mes_model_usage_policy WHERE tenant_id=?",Integer.class,TenantContextHolder.getRequiredTenantId());
        usage.savePolicy(new MesModelUsageVO.PolicySaveRequest(version==null?0:version,true,tenant,user,
                List.of(new MesModelUsageVO.PriceCard("CHAT","openai","native-test","1000000","1000000",null)),"隔离MySQL真实服务配额测试"));
    }
    private MesModelUsageService.Reservation reserve(MesModelUsageService instance){return instance.reserve(MesModelUsageService.Purpose.KNOWLEDGE_CHAT,"CHAT","openai","native-test",10,10);}
    private MesModelUsageService.Settlement known(long input,long output){return new MesModelUsageService.Settlement("SUCCESS",input,output,input+output,"native-test",200,1,true,false);}
    private Map<String,Object> log(String id){return jdbc.queryForMap("SELECT * FROM mes_model_usage_log WHERE id=?",id);}
    private long metric(String field,String subject,String window){
        assert Set.of("used_calls","reserved_calls","unknown_calls","known_tokens","reserved_tokens","held_unknown_tokens","active_count","admitted").contains(field);
        return jdbc.queryForObject("SELECT "+field+" FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type=? AND window_type=?",Long.class,subject,window);
    }
    enum Quota {TENANT_DAY_CALLS,TENANT_MONTH_CALLS,USER_DAY_CALLS,USER_MONTH_CALLS,
        TENANT_DAY_TOKENS,TENANT_MONTH_TOKENS,USER_DAY_TOKENS,USER_MONTH_TOKENS,
        TENANT_DAY_AMOUNT,TENANT_MONTH_AMOUNT,USER_DAY_AMOUNT,USER_MONTH_AMOUNT,
        TENANT_MINUTE,USER_MINUTE,TENANT_LIVE,USER_LIVE}
    private MesModelUsageVO.Limits limited(Quota quota){
        String key=quota.name().replaceFirst("^(TENANT|USER)_","");
        return new MesModelUsageVO.Limits(key.equals("DAY_CALLS")?"1":"1000",key.equals("MONTH_CALLS")?"1":"1000",
                key.equals("DAY_TOKENS")?"20":"1000000",key.equals("MONTH_TOKENS")?"20":"1000000",
                key.equals("DAY_AMOUNT")?"20":null,key.equals("MONTH_AMOUNT")?"20":null,
                key.equals("LIVE")?1:100,key.equals("MINUTE")?1:1000);
    }
    private record Reserved(MesModelUsageService.Reservation reservation,RuntimeException rejection){}
    private List<Reserved> race(long firstTenant,long firstUser,long secondTenant,long secondUser) throws Exception {
        var workers=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        long[] tenants={firstTenant,secondTenant},users={firstUser,secondUser};
        var instances=List.of(usage,secondUsage);var futures=new ArrayList<Future<Reserved>>();
        try{
            for(int index=0;index<2;index++){final int selected=index;futures.add(workers.submit(()->{
                login(tenants[selected],users[selected]);ready.countDown();
                try{assertTrue(start.await(10,TimeUnit.SECONDS));try{return new Reserved(reserve(instances.get(selected)),null);}catch(RuntimeException ex){return new Reserved(null,ex);}}
                finally{clearLogin();}
            }));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            var result=new ArrayList<Reserved>();for(var future:futures)result.add(future.get(15,TimeUnit.SECONDS));return result;
        }finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @EnumSource(Quota.class)
    void twoIndependentServicesCanOnlyReserveTheLastApplicableQuotaOnce(Quota quota) throws Exception {
        policy(quota.name().startsWith("TENANT")?limited(quota):generous(),quota.name().startsWith("USER")?limited(quota):generous());
        var results=race(1,10,1,10);
        assertEquals(1,results.stream().filter(row->row.reservation()!=null).count(),quota.name());
        var failures=results.stream().filter(row->row.rejection()!=null).toList();assertEquals(1,failures.size());
        assertInstanceOf(ServiceException.class,failures.get(0).rejection(),"No deadlock/SQL exception is an acceptable quota denial");
        assertEquals(1047140004,((ServiceException)failures.get(0).rejection()).getCode());
        assertEquals(1,metric("reserved_calls","TENANT","DAY"));assertEquals(20,metric("reserved_tokens","TENANT","DAY"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log WHERE status='DENIED'",Integer.class));
    }
    @Test void differentTenantsInitializeTheirFirstBucketsConcurrentlyWithoutEmptyRangeDeadlock() throws Exception {
        login(2,20);policy(generous(),generous());login(1,10);
        var results=race(1,10,2,20);
        assertTrue(results.stream().allMatch(row->row.reservation()!=null&&row.rejection()==null),results.toString());
        assertEquals(16,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_bucket",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log WHERE status='RESERVED'",Integer.class));
    }
    @Test void oldRepeatableReadSnapshotCannotHideACommittedReservation() throws Exception {
        policy(limited(Quota.TENANT_DAY_CALLS),generous());var worker=Executors.newSingleThreadExecutor();var writerConnection=new AtomicLong();
        try{transaction().executeWithoutResult(status->{
            long oldConnection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_bucket",Integer.class));
            try{worker.submit(()->{login(1,10);try{
                var own=new TransactionTemplate(new DataSourceTransactionManager(source));own.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                own.executeWithoutResult(ignored->{writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));reserve(secondUsage);});
            }finally{clearLogin();}}).get(10,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
            assertNotEquals(oldConnection,writerConnection.get());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_bucket",Integer.class),"Real old RR snapshot must remain empty");
            assertEquals(1047140004,assertThrows(ServiceException.class,()->reserve(usage)).getCode());
        });}finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,metric("reserved_calls","TENANT","DAY"));
    }
    @Test void independentUsageCommitsSurviveLaterBusinessRollbackAndSettlementIsIdempotent(){
        var reservation=reserve(usage);usage.dispatched(reservation.id());
        assertThrows(IllegalStateException.class,()->transaction().executeWithoutResult(status->{usage.settle(reservation.id(),known(3,2));throw new IllegalStateException("forced unrelated business rollback");}));
        assertEquals("SETTLED",log(reservation.id()).get("status"));assertEquals(1,metric("used_calls","TENANT","DAY"));
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event",Integer.class);
        usage.settle(reservation.id(),known(3,2));assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event",Integer.class));
        assertEquals(5,metric("known_tokens","TENANT","DAY"));
        assertThrows(ServiceException.class,()->usage.settle(reservation.id(),known(4,2)));
        assertEquals(5,metric("known_tokens","TENANT","DAY"));
    }
    @Test void settlementAfterShanghaiLeapMonthBoundaryUsesOriginalAdmissionWindows(){
        var reservation=reserve(usage);usage.dispatched(reservation.id());clock.advance(Duration.ofSeconds(2));usage.settle(reservation.id(),known(3,2));
        assertEquals("2024-02-29 00:00:00",jdbc.queryForObject("SELECT DATE_FORMAT(window_start,'%Y-%m-%d %H:%i:%s') FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
        assertEquals("2024-02-01 00:00:00",jdbc.queryForObject("SELECT DATE_FORMAT(window_start,'%Y-%m-%d %H:%i:%s') FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='MONTH'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_bucket WHERE window_start>='2024-03-01'",Integer.class));
        assertEquals(5,metric("known_tokens","TENANT","MONTH"));
    }
    @Test void accountingWindowsAndAuditTimesRemainShanghaiWhenJvmDefaultIsUtc(){
        TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try{
            var reservation=reserve(usage);usage.dispatched(reservation.id());usage.settle(reservation.id(),known(3,2));
            assertEquals("2024-02-29 00:00:00",jdbc.queryForObject("SELECT DATE_FORMAT(window_start,'%Y-%m-%d %H:%i:%s') FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
            assertEquals("2024-02-01 00:00:00",jdbc.queryForObject("SELECT DATE_FORMAT(window_start,'%Y-%m-%d %H:%i:%s') FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='MONTH'",String.class));
            assertEquals("2024-02-29 23:59:59",jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM mes_model_usage_log WHERE id=?",String.class,reservation.id()));
            var summary=json.valueToTree(usage.summary());var day=summary.path("tenant").path("day");
            assertEquals("1",day.path("calls").path("used").asText());assertEquals("5",day.path("tokens").path("used").asText());
            var start=OffsetDateTime.parse(day.path("start").asText());assertEquals(LocalDate.of(2024,2,29),start.toLocalDate());assertEquals(LocalTime.MIDNIGHT,start.toLocalTime());assertEquals(ZoneOffset.ofHours(8),start.getOffset());
        }finally{TimeZone.setDefault(original);}
    }
    @Test void actualOverageBeyondLongCumulativeTokensAndSixteenDigitMoneyIsPreserved(){
        var maximum=new MesModelUsageVO.Limits("1000","1000",Long.toString(Long.MAX_VALUE),Long.toString(Long.MAX_VALUE),null,null,100,1000);
        policy(maximum,maximum);
        var first=reserve(usage);var second=reserve(secondUsage);usage.dispatched(first.id());secondUsage.dispatched(second.id());
        usage.settle(first.id(),known(Long.MAX_VALUE,0));secondUsage.settle(second.id(),known(Long.MAX_VALUE,0));
        assertEquals("18446744073709551614",jdbc.queryForObject("SELECT CAST(known_tokens AS CHAR) FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
        assertEquals("18446744073709551614.00000000",jdbc.queryForObject("SELECT CAST(calculated_amount AS CHAR) FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
        assertEquals(1047140004,assertThrows(ServiceException.class,()->reserve(usage)).getCode());
        assertEquals(2,metric("used_calls","TENANT","DAY"));
    }
    @Test void elevenAdmittedCallsCanSettleMaximumActualUsageAndRatesWithoutThirtyDigitMoneyOverflow(){
        var maximum=new MesModelUsageVO.Limits("1000","1000",Long.toString(Long.MAX_VALUE),Long.toString(Long.MAX_VALUE),null,null,100,1000);
        int version=jdbc.queryForObject("SELECT version FROM mes_model_usage_policy WHERE tenant_id=1",Integer.class);
        usage.savePolicy(new MesModelUsageVO.PolicySaveRequest(version,true,maximum,maximum,
                List.of(new MesModelUsageVO.PriceCard("CHAT","openai","native-test","9999999999999999.99999999","9999999999999999.99999999",null)),"真实累计金额必须能容纳已准入调用的超额证据"));
        var admitted=new ArrayList<MesModelUsageService.Reservation>();for(int index=0;index<11;index++)admitted.add(reserve(usage));
        assertEquals(11,metric("active_count","TENANT","LIVE"));
        for(var reservation:admitted){usage.dispatched(reservation.id());usage.settle(reservation.id(),known(Long.MAX_VALUE,0));}
        assertEquals("1014570924054025338769998985429.07594595",jdbc.queryForObject("SELECT CAST(calculated_amount AS CHAR) FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
        assertEquals("101457092405402533877",jdbc.queryForObject("SELECT CAST(known_tokens AS CHAR) FROM mes_model_usage_bucket WHERE tenant_id=1 AND subject_type='TENANT' AND window_type='DAY'",String.class));
        assertEquals(11,metric("used_calls","TENANT","DAY"));assertEquals(0,metric("active_count","TENANT","LIVE"));
        assertEquals(1047140004,assertThrows(ServiceException.class,()->reserve(usage)).getCode());
    }
    @Test void staleClosureThenLateGenuineSettlementChangesDeltasAndReleasesActiveExactlyOnce(){
        var reservation=reserve(usage);usage.dispatched(reservation.id());int dispatchedVersion=((Number)log(reservation.id()).get("version")).intValue();
        assertThrows(ServiceException.class,()->usage.reconcile(new MesModelUsageVO.ReconcileRequest(reservation.id(),dispatchedVersion,"CLOSE_AS_UNKNOWN",null,null,null,"仍在实际HTTP处理期限内不能核销")));
        assertEquals(1,metric("active_count","TENANT","LIVE"));clock.advance(Duration.ofMinutes(3));
        usage.reconcile(new MesModelUsageVO.ReconcileRequest(reservation.id(),dispatchedVersion,"CLOSE_AS_UNKNOWN",null,null,null,"处理期限已过，先保守保留预算"));
        assertEquals(0,metric("active_count","TENANT","LIVE"));assertEquals(20,metric("held_unknown_tokens","TENANT","DAY"));
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event",Integer.class);
        assertThrows(ServiceException.class,()->usage.reconcile(new MesModelUsageVO.ReconcileRequest(reservation.id(),dispatchedVersion,"CONFIRM_NOT_DISPATCHED",null,null,null,"旧版本不得变更预算")));
        assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event",Integer.class));
        usage.settle(reservation.id(),known(3,2));usage.settle(reservation.id(),known(3,2));
        assertEquals(0,metric("active_count","TENANT","LIVE"));assertEquals(0,metric("held_unknown_tokens","TENANT","DAY"));
        assertEquals(5,metric("known_tokens","TENANT","DAY"));assertEquals(1,metric("used_calls","TENANT","DAY"));
    }

    @Test void providerWaitDoesNotHoldSessionRowLock() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();
            try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Native owner did not release provider");}
            catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
            byte[] response="{\"model\":\"native-test\",\"choices\":[{\"message\":{\"content\":\"原生测试答复\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        var workers=Executors.newSingleThreadExecutor();Future<Map<String,Object>> answer=null;
        try {
            gateway.save(new MesAssistantModelGateway.Settings("openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","native-test","local-test-only-key",false,.2,128,true,2048),10);
            long session=assistant.createSession("短事务原生证明",10);
            answer=workers.submit(()->{login(1,10);try{return assistant.send(session,"原生并发锁验证",List.of(),10);}finally{clearLogin();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS),"Actual production caller must reach local HTTP");assertEquals(1,requests.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND role='user' AND status='PROCESSING' AND deleted=0",Integer.class,session),"The question must already be durable while the provider is working");
            assertDoesNotThrow(()->transaction().executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM mes_assistant_session WHERE id=? FOR UPDATE NOWAIT",Long.class,session);
                assertEquals(1,jdbc.queryForList("SELECT tenant_id FROM mes_model_usage_policy WHERE tenant_id=1 FOR UPDATE NOWAIT").size());
                assertEquals(1,jdbc.queryForList("SELECT id FROM mes_model_usage_log WHERE tenant_id=1 AND status='DISPATCHED' FOR UPDATE NOWAIT").size());
                assertEquals(8,jdbc.queryForList("SELECT tenant_id FROM mes_model_usage_bucket WHERE tenant_id=1 ORDER BY tenant_id,subject_type,subject_id,window_type,window_start FOR UPDATE NOWAIT").size());
            }),"The production caller must release its actual InnoDB session lock before HTTP");
        } finally {
            release.countDown();if(answer!=null)assertEquals("ANSWERED",answer.get(20,TimeUnit.SECONDS).get("status"));
            workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));server.stop(0);serverThreads.shutdownNow();assertTrue(serverThreads.awaitTermination(5,TimeUnit.SECONDS));
        }
        assertEquals(1,metric("used_calls","TENANT","DAY"));assertEquals(5,metric("known_tokens","TENANT","DAY"));
    }
    private static final class BlockingProvider implements AutoCloseable {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger requests=new AtomicInteger();final ExecutorService threads=Executors.newCachedThreadPool();final HttpServer server;
        BlockingProvider() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
            server.createContext("/v1/chat/completions",exchange->{
                requests.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Provider was not released by native test");}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                byte[] response="{\"model\":\"native-test\",\"choices\":[{\"message\":{\"content\":\"真实本地响应\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
            });server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
        void awaitRequest() throws Exception{assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(1,requests.get());}
        @Override public void close() throws Exception{release.countDown();server.stop(0);threads.shutdownNow();assertTrue(threads.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private void configure(BlockingProvider provider){gateway.save(new MesAssistantModelGateway.Settings("openai",provider.base(),"native-test","local-test-only-key",false,.2,128,true,2048),10);}
    @ParameterizedTest @ValueSource(strings={"DELETE_SESSION","FAIL_FINALIZE"})
    void successfulProviderUsageSurvivesDeletedSessionOrFailedFinalization(String failure) throws Exception {
        try(var provider=new BlockingProvider()){
            configure(provider);long session=assistant.createSession("失败后费用仍有审计",10);var worker=Executors.newSingleThreadExecutor();Future<Map<String,Object>> future=null;
            try{
                future=worker.submit(()->{login(1,10);try{return assistant.send(session,"原问题必须保留",List.of(),10);}finally{clearLogin();}});
                provider.awaitRequest();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND status='PROCESSING'",Integer.class,session));
                if(failure.equals("DELETE_SESSION"))assistant.deleteSession(session,10);
                else jdbc.execute("CREATE TRIGGER native_fail_assistant BEFORE INSERT ON mes_assistant_message FOR EACH ROW BEGIN IF NEW.role='assistant' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced native finalization failure'; END IF; END");
                provider.release.countDown();final var submitted=future;
                assertThrows(ExecutionException.class,()->submitted.get(20,TimeUnit.SECONDS));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log WHERE status='SETTLED'",Integer.class));
                assertEquals(1,metric("used_calls","TENANT","DAY"));assertEquals(5,metric("known_tokens","TENANT","DAY"));
                assertNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,session));
                if(failure.equals("DELETE_SESSION")){
                    assertEquals(1,jdbc.queryForObject("SELECT deleted+0 FROM mes_assistant_session WHERE id=?",Integer.class,session));
                    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND deleted=0",Integer.class,session));
                }else{
                    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND role='user' AND status='MODEL_ERROR' AND content_text='原问题必须保留' AND deleted=0",Integer.class,session));
                    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND role='assistant'",Integer.class,session));
                }
            }finally{provider.release.countDown();if(future!=null&&!future.isDone())try{future.get(20,TimeUnit.SECONDS);}catch(ExecutionException ignored){}worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void concurrentRealGatewaysAdmitOnlyOneProviderCallAndHoldNoUsageLocks() throws Exception {
        policy(limited(Quota.TENANT_DAY_CALLS),generous());
        try(var provider=new BlockingProvider()){
            configure(provider);var secondGateway=new MesAssistantModelGateway(jdbc,json,secrets,secondUsage,"http://127.0.0.1:1",10000);
            var workers=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var finished=new CountDownLatch(1);
            var gateways=List.of(gateway,secondGateway);var futures=new ArrayList<Future<RuntimeException>>();
            try{
                for(var selected:gateways)futures.add(workers.submit(()->{login(1,10);ready.countDown();try{assertTrue(start.await(10,TimeUnit.SECONDS));try{selected.complete(MesModelUsageService.Purpose.BUSINESS_INSIGHT,List.of(Map.of("role","user","content","native ping")));return null;}catch(RuntimeException ex){return ex;}}finally{finished.countDown();clearLogin();}}));
                assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();provider.awaitRequest();assertTrue(finished.await(10,TimeUnit.SECONDS),"The denied contender must finish while the winner is still inside HTTP");
                assertEquals(1,provider.requests.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_log WHERE status='DENIED'",Integer.class));
                assertDoesNotThrow(()->transaction().executeWithoutResult(status->{
                    assertEquals(1,jdbc.queryForList("SELECT tenant_id FROM mes_model_usage_policy WHERE tenant_id=1 FOR UPDATE NOWAIT").size());
                    assertEquals(8,jdbc.queryForList("SELECT tenant_id FROM mes_model_usage_bucket WHERE tenant_id=1 ORDER BY tenant_id,subject_type,subject_id,window_type,window_start FOR UPDATE NOWAIT").size());
                    assertEquals(2,jdbc.queryForList("SELECT id FROM mes_model_usage_log WHERE tenant_id=1 ORDER BY id FOR UPDATE NOWAIT").size());
                }));
                provider.release.countDown();var failures=new ArrayList<RuntimeException>();int successes=0;
                for(var future:futures){var result=future.get(20,TimeUnit.SECONDS);if(result==null)successes++;else failures.add(result);}
                assertEquals(1,successes);assertEquals(1,failures.size());assertEquals(1047140004,assertInstanceOf(ServiceException.class,failures.get(0)).getCode());
                assertEquals(1,metric("used_calls","TENANT","DAY"));
            }finally{start.countDown();provider.release.countDown();for(var future:futures)if(!future.isDone())future.get(20,TimeUnit.SECONDS);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
}
