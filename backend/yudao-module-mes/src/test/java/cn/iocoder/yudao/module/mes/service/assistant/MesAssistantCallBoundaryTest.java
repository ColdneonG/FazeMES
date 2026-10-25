package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway.Purpose;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction advice: a plain new service could not reveal the original send transaction. */
class MesAssistantCallBoundaryTest {
    private JdbcTemplate jdbc;
    private MesAssistantService service;
    private MesAssistantModelGateway gateway;
    private DataSourceTransactionManager manager;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:assistant_call_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/assistant_rag.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);gateway=mock(MesAssistantModelGateway.class);
        when(gateway.status()).thenReturn(Map.of("configured",true,"model","test-model"));
        when(gateway.inputBudget()).thenReturn(30000);
        var target=new MesAssistantService(jdbc,new ObjectMapper(),new MesAssistantDocumentParser(),new MesAssistantRetriever(),gateway);
        var factory=new ProxyFactory(target);
        manager=new DataSourceTransactionManager(ds);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(MesAssistantService)factory.getProxy();
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        TenantContextHolder.setTenantId(1L);
    }
    @AfterEach void cleanup(){TenantContextHolder.clear();jdbc.execute("DROP ALL OBJECTS");}
    @Test void modelCallHasNoCallerTransactionAfterTheRealSpringProxyIsApplied(){
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Knowledge HTTP must execute after the session transaction has committed");
            assertNotNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT version FROM mes_assistant_session",Integer.class));
            assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM mes_assistant_message",String.class));
            assertEquals("点检要求",jdbc.queryForObject("SELECT content_text FROM mes_assistant_message",String.class));
            return "模型返回的测试答复";
        });
        long id=service.createSession(null,10L);
        var response=service.send(id,"点检要求",List.of(),10L);
        assertEquals("ANSWERED",response.get("status"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message",Integer.class));
    }
    @Test void duplicatePendingRequestIsRejectedWithoutASecondModelCall() throws Exception {
        long id=service.createSession(null,10L);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "答复";});
        var worker=Executors.newSingleThreadExecutor();
        try{
            var first=worker.submit(()->{TenantContextHolder.setTenantId(1L);try{return service.send(id,"首个问题",List.of(),10L);}finally{TenantContextHolder.clear();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertThrows(ServiceException.class,()->service.send(id,"重复问题",List.of(),10L));
            verify(gateway,times(1)).complete(eq(Purpose.KNOWLEDGE_CHAT),anyList());
            release.countDown();assertEquals("ANSWERED",first.get(10,TimeUnit.SECONDS).get("status"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message",Integer.class));
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void deletionDuringTheModelCallCannotBeUndoneByFinalization(){
        long id=service.createSession(null,10L);
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{service.deleteSession(id,10L);return "迟到答复";});
        assertThrows(ServiceException.class,()->service.send(id,"问题",List.of(),10L));
        assertEquals(1,jdbc.queryForObject("SELECT deleted FROM mes_assistant_session WHERE id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE deleted=0",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE deleted=1",Integer.class));
        assertNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
    }
    @Test void budgetDenialPersistsRealRetrievalCitationsAndReleasesTheClaim(){
        long base=service.saveBase(null,"检验依据","",10L);
        long document=((Number)service.upload(base,"首件放行","首检.md","首件检验合格后才可放行生产。".getBytes(StandardCharsets.UTF_8),10L).get("id")).longValue();service.publish(document,true);
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenThrow(new ServiceException(1_047_140_004,"模型调用已达到当前配额"));
        long id=service.createSession(null,10L);var result=service.send(id,"首件检验放行要求",List.of(base),10L);
        assertEquals("MODEL_ERROR",result.get("status"));assertNull(result.get("answer"));assertFalse(((List<?>)result.get("citations")).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE role='user' AND status='MODEL_ERROR'",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT citations_json FROM mes_assistant_message",String.class).contains("首件检验"));
        assertNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
    }
    @Test void failedRetrievalReleasesItsClaimAndDoesNotReachTheGateway(){
        long id=service.createSession(null,10L);assertThrows(ServiceException.class,()->service.send(id,"问题",List.of(999L),10L));
        assertNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
        assertEquals("问题",jdbc.queryForObject("SELECT content_text FROM mes_assistant_message",String.class));
        assertEquals("MODEL_ERROR",jdbc.queryForObject("SELECT status FROM mes_assistant_message",String.class));
        verify(gateway,never()).complete(any(Purpose.class),anyList());
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenReturn("后续正常答复");
        assertEquals("ANSWERED",service.send(id,"后续问题",List.of(),10L).get("status"));
    }
    @Test void finalizationFailurePreservesTheQuestionAndCitationsAndReleasesOnlyItsOwnClaim(){
        long base=service.saveBase(null,"检验依据","",10L);
        long doc=((Number)service.upload(base,"问题记录","记录.md","发生问题必须记录问题依据。".getBytes(StandardCharsets.UTF_8),10L).get("id")).longValue();service.publish(doc,true);
        long id=service.createSession(null,10L);when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenReturn("答复");
        jdbc.execute("ALTER TABLE mes_assistant_message ADD CONSTRAINT reject_assistant CHECK(role<>'assistant')");
        assertThrows(RuntimeException.class,()->service.send(id,"问题",List.of(base),10L));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE role='user' AND status='MODEL_ERROR'",Integer.class));
        assertEquals("问题",jdbc.queryForObject("SELECT content_text FROM mes_assistant_message",String.class));
        assertTrue(jdbc.queryForObject("SELECT citations_json FROM mes_assistant_message",String.class).contains("documentId"));
        assertNull(jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
        jdbc.execute("ALTER TABLE mes_assistant_message DROP CONSTRAINT reject_assistant");
        assertEquals("ANSWERED",service.send(id,"重试问题",List.of(),10L).get("status"));
    }
    @Test void activeOuterBusinessTransactionIsRejectedBeforeAClaimOrModelCall(){
        long id=service.createSession(null,10L);
        new TransactionTemplate(manager).executeWithoutResult(status->{assertThrows(ServiceException.class,()->service.send(id,"问题",List.of(),10L));status.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("SELECT version FROM mes_assistant_session WHERE id=?",Integer.class,id));
        verify(gateway,never()).complete(any(Purpose.class),anyList());
    }
    @Test void aTitleRenamedWhileWaitingIsPreservedByFinalization(){
        long id=service.createSession(null,10L);when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{service.renameSession(id,"人工维护的标题",10L);return "答复";});
        service.send(id,"不应覆盖标题的问题",List.of(),10L);assertEquals("人工维护的标题",service.session(id,10L).get("title"));
    }
    @Test void staleFinalizationCannotClearAnotherRequestOrCreateAnAssistantReply(){
        long id=service.createSession(null,10L);String replacement=UUID.randomUUID().toString();
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{jdbc.update("UPDATE mes_assistant_session SET version=version+1,pending_request_id=? WHERE id=?",replacement,id);return "迟到答复";});
        assertThrows(ServiceException.class,()->service.send(id,"问题",List.of(),10L));
        assertEquals(replacement,jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE role='user' AND status='MODEL_ERROR'",Integer.class));
    }
    @Test void committedClobHistoryReachesTheNextModelAsExactText(){
        long id=service.createSession(null,10L);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(gateway.complete(eq(Purpose.KNOWLEDGE_CHAT),anyList())).thenAnswer(invocation->{
            if(calls.incrementAndGet()==2){
                List<Map<String,String>> prompt=invocation.getArgument(1);
                assertTrue(prompt.stream().anyMatch(item->item.get("role").equals("user")&&item.get("content").equals("第一条原始问题")));
                assertTrue(prompt.stream().anyMatch(item->item.get("role").equals("assistant")&&item.get("content").equals("原始答复内容")));
            }
            return "原始答复内容";
        });
        service.send(id,"第一条原始问题",List.of(),10L);service.send(id,"第二条问题",List.of(),10L);
        assertEquals(2,calls.get());assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message",Integer.class));
        assertEquals(4,jdbc.queryForObject("SELECT version FROM mes_assistant_session WHERE id=?",Integer.class,id));
    }
    @Test void theCommittedQuestionSurvivesWhenNoFinalizerEverRuns(){
        long id=service.createSession(null,10L);var store=new MesAssistantMessageStore(jdbc,new ObjectMapper(),manager);
        var claim=store.claim(id,10L,"崩溃前已经提交的问题");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM mes_assistant_message WHERE id=?",String.class,claim.userMessageId()));
        assertEquals("崩溃前已经提交的问题",jdbc.queryForObject("SELECT content_text FROM mes_assistant_message WHERE id=?",String.class,claim.userMessageId()));
        assertEquals(claim.requestId(),jdbc.queryForObject("SELECT pending_request_id FROM mes_assistant_session WHERE id=?",String.class,id));
        var history=(List<?>)service.session(id,10L).get("messages");assertEquals(1,history.size());
        assertThrows(ServiceException.class,()->service.send(id,"再次发送",List.of(),10L));
        verify(gateway,never()).complete(any(Purpose.class),anyList());
    }
}
