package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MesInsightWorkflowTest {
    private JdbcTemplate jdbc;
    private MesInsightWorkflow workflow;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:insight_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/production_insight.sql")).execute(source);
        jdbc=new JdbcTemplate(source);
        var proxy=new ProxyFactory(new MesInsightWorkflow(jdbc));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        workflow=(MesInsightWorkflow)proxy.getProxy();
        login(1,10);
        jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,status,deleted) VALUES(10,1,'local',0,0),(20,2,'foreign',0,0)");
        jdbc.update("INSERT INTO mes_pro_work_order(id,tenant_id,code,deleted) VALUES(100,1,'local',0),(200,2,'foreign',0),(300,1,'deleted',1)");
        jdbc.update("INSERT INTO mes_md_workstation(id,tenant_id,name,deleted) VALUES(1,1,'local',0),(2,2,'foreign',0)");
        jdbc.update("INSERT INTO mes_pro_andon_record(id,tenant_id,work_order_id,workstation_id,status,deleted) VALUES(1,1,100,1,0,0),(2,2,200,2,0,0)");
    }
    private void login(long tenant,long user) {
        TenantContextHolder.setTenantId(tenant);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());
    }
    @AfterEach void clear() { TenantContextHolder.clear();SecurityContextHolder.clearContext();if(jdbc!=null)jdbc.execute("DROP ALL OBJECTS"); }
    private long create() {return workflow.create(new IncidentCreate("case","QUALITY",100L,1L,1L,10L,null,"source"));}
    private IncidentMove move(long id,int version,String state,String cause,String action,String evidence) {
        return new IncidentMove(id,version,state,10L,null,cause,action,evidence,"test transition");
    }
    @Test void verificationGateVersionAuditAndAndonCloseAreAtomic() {
        long id=create();
        assertThrows(ServiceException.class,()->workflow.move(move(id,0,"CLOSED","cause","action","pass")));
        workflow.move(move(id,0,"IN_PROGRESS","","",""));
        assertThrows(ServiceException.class,()->workflow.move(move(id,0,"VERIFYING","cause","action","")));
        assertThrows(ServiceException.class,()->workflow.move(move(id,1,"VERIFYING","","action","")));
        assertEquals(2,((List<?>)workflow.history(id)).size());
        workflow.move(move(id,1,"VERIFYING","confirmed cause","fixed setting",""));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM mes_pro_andon_record WHERE id=1",Integer.class));
        assertThrows(ServiceException.class,()->workflow.move(move(id,2,"CLOSED","cause","action","")));
        workflow.move(move(id,2,"CLOSED","confirmed cause","fixed setting","inspection passed"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_pro_andon_record WHERE id=1",Integer.class));
        assertEquals(4,((List<?>)workflow.history(id)).size());
        workflow.move(move(id,3,"IN_PROGRESS","recurrence","investigate",""));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM mes_pro_andon_record WHERE id=1",Integer.class));
        assertNull(jdbc.queryForObject("SELECT closed_at FROM mes_insight_incident WHERE id=?",java.sql.Timestamp.class,id));
    }
    @Test void foreignDeletedReferencesAndForeignIncidentHistoryAreNeverAccessible() {
        for(long order:List.of(200L,300L)) assertThrows(ServiceException.class,()->workflow.create(new IncidentCreate("x","OTHER",order,null,null,null,null,"")));
        assertThrows(ServiceException.class,()->workflow.create(new IncidentCreate("x","OTHER",100L,2L,null,null,null,"")));
        assertThrows(ServiceException.class,()->workflow.create(new IncidentCreate("x","OTHER",100L,null,null,20L,null,"")));
        assertThrows(ServiceException.class,()->workflow.create(new IncidentCreate("x","OTHER",null,null,2L,null,null,"")));
        long id=create();login(2,20);
        assertThrows(ServiceException.class,()->workflow.history(id));
        assertThrows(ServiceException.class,()->workflow.move(move(id,0,"IN_PROGRESS","","","")));
    }
    @Test void draftConversionIsIdempotentAndPrivateToItsCreator() {
        long draft=workflow.saveDraft(new DraftCreate("suggestion","verify the source",100L));
        login(1,11);assertThrows(ServiceException.class,()->workflow.convertDraft(draft));login(1,10);
        long id=workflow.convertDraft(draft);assertEquals(id,workflow.convertDraft(draft));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_incident",Integer.class));
    }
    @Test void concurrentEditorsCannotOverwriteEachOthersTransitions() throws Exception {
        long id=create();var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<2;i++) jobs.add(()->{login(1,10);try {workflow.move(move(id,0,"IN_PROGRESS","","",""));return true;} catch(ServiceException ex){return false;} finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}});
            int wins=0;for(var result:pool.invokeAll(jobs)) if(result.get())wins++;
            assertEquals(1,wins);assertEquals(2,((List<?>)workflow.history(id)).size());
        } finally {pool.shutdownNow();}
    }
}
