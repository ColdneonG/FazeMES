package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightInventoryFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real parser -> real fixed SQL tool -> real immutable conversation/evidence persistence. */
class MesInsightBusinessIntegrationTest {
    JdbcTemplate jdbc; SecurityFrameworkService security; MesInsightInventoryQuery query;
    MesInsightConversationStore store; MesInsightBusinessAssistant assistant; MesInsightService legacy;
    DataSourceTransactionManager manager;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:insight_integration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); create(jdbc,false);
        new ResourceDatabasePopulator(new ClassPathResource("sql/insight_business.sql")).execute(ds);
        manager=new DataSourceTransactionManager(ds); security=mock(SecurityFrameworkService.class); when(security.hasPermission(anyString())).thenReturn(true);
        query=new MesInsightInventoryQuery(jdbc,security,manager,Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"),ZoneId.of("Asia/Shanghai")));
        store=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,query); legacy=mock(MesInsightService.class);
        assistant=new MesInsightBusinessAssistant(jdbc,security,store,query,legacy);
        TenantContextHolder.setTenantId(1L); SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(10L).setTenantId(1L).setUserType(2),new MockHttpServletRequest());
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); jdbc.execute("DROP ALL OBJECTS"); }
    @SuppressWarnings("unchecked") Map<String,Object> object(Map<String,Object> value,String key) { return (Map<String,Object>)value.get(key); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(Map<String,Object> value,String key) { return (List<Map<String,Object>>)value.get(key); }
    long id(Map<String,Object> value,String key) { return ((Number)value.get(key)).longValue(); }
    String json(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); }
        catch(Exception ex) { throw new AssertionError(ex); }
    }
    Question next(Map<String,Object> value,String question) { return new Question(question,false,id(value,"conversationId"),((Number)value.get("version")).intValue(),null); }
    Map<String,Object> select(Map<String,Object> answer,String contains) {
        var clarification=object(answer,"clarification"); var choice=list(clarification,"choices").stream().filter(c->c.get("label").toString().contains(contains)).findFirst().orElseThrow();
        assertDoesNotThrow(()->UUID.fromString(choice.get("id").toString()),"Client choice must be opaque, not a raw document ID");
        return assistant.ask(new Question(null,false,id(answer,"conversationId"),((Number)answer.get("version")).intValue(),
                new ClarificationChoice(clarification.get("token").toString(),choice.get("id").toString())));
    }
    @Test void chineseMonthlyCategoryFollowupsPersistExactFiltersMixedUnitsAndImmutablePages() {
        for(int i=1;i<=26;i++) {
            int warehouse=i==26?2:1;
            stock(jdbc,i,1,warehouse,"B"+i,"1.000001","2026-07-01 00:00:00");
            tx(jdbc,i,i,1,warehouse,"B"+i,"1","2026-07-01 00:00:00",110,i,"R"+i);
            tx(jdbc,100+i,i,1,warehouse,"B"+i,"0.000001","2026-09-01 00:00:00",110,100+i,"R-SEP"+i);
        }
        stock(jdbc,50,2,1,"KG-1","9.5","2026-07-01 00:00:00"); tx(jdbc,50,50,2,1,"KG-1","9.5","2026-07-01 00:00:00",110,50,"KG-IN");
        var first=assistant.ask(new Question("查二〇二六年九月原材料库存台账，按批次",false));
        assertEquals("ANSWERED",first.get("status")); assertEquals("inventory",first.get("topic"));
        var evidence=object(first,"evidence"); assertEquals(27,evidence.get("total")); assertEquals(20,list(evidence,"list").size());
        assertEquals(2,list(evidence,"unitGroups").size());
        assertEquals("26.000026",list(evidence,"unitGroups").get(0).get("closingQuantity"));
        assertEquals("0.000026",list(evidence,"unitGroups").get(0).get("inboundQuantity"));
        assertEquals("9.5",list(evidence,"unitGroups").get(1).get("closingQuantity"));
        assertEquals(1L,id(object(object(evidence,"filters"),"category"),"id"));
        var second=assistant.ask(next(first,"那8月份，只看原料仓"));
        assertEquals("ANSWERED",second.get("status")); var secondEvidence=object(second,"evidence");
        assertEquals(8,object(secondEvidence,"period").get("month")); assertEquals(2026,object(secondEvidence,"period").get("year"));
        assertEquals(1L,id(object(object(secondEvidence,"filters"),"warehouse"),"id"));
        assertEquals("25",list(secondEvidence,"unitGroups").get(0).get("closingQuantity"));
        var third=assistant.ask(next(second,"按仓库"));
        assertEquals("ANSWERED",third.get("status")); assertEquals("WAREHOUSE",object(object(third,"evidence"),"filters").get("groupBy"));
        assertEquals(2,object(third,"evidence").get("total")); assertEquals(3,third.get("version"));
        var reloaded=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,query).get(id(first,"conversationId"),null,20);
        assertEquals(3,list(reloaded,"turns").size()); assertEquals(3,object(reloaded,"conversation").get("version"));
        // Current business data can change; evidence pages must keep the first turn's exact scope and facts.
        jdbc.update("UPDATE mes_md_item SET unit_measure_id=2 WHERE id=1");
        jdbc.update("UPDATE mes_wm_material_stock SET quantity=quantity+999 WHERE id=1");
        var page=store.evidence(id(first,"turnId"),2,20);
        assertEquals(7,list(page,"list").size()); assertEquals(27,page.get("total")); assertEquals(evidence.get("asOf"),page.get("asOf"));
        // JDBC Long IDs and Jackson's small Integer IDs have identical JSON number semantics.
        assertEquals(json(evidence.get("unitGroups")),json(page.get("unitGroups")));
        assertEquals(json(evidence.get("period")),json(page.get("period")));
        assertEquals(json(evidence.get("filters")),json(page.get("filters")));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class)); verifyNoInteractions(legacy);
    }
    @Test void documentAmbiguityOpaqueChoiceSelectsActualSalesOrPairedTransfer() {
        stock(jdbc,1,1,1,"B1","8","2026-08-01 00:00:00"); tx(jdbc,1,1,1,1,"B1","10","2026-08-01 00:00:00",110,10,"RECEIPT");
        tx(jdbc,2,1,1,1,"B1","-2","2026-09-01 00:00:00",118,20,"DOC-1");
        tx(jdbc,3,1,1,1,"B1","-1","2026-09-01 00:00:00",111,30,"DOC-1"); tx(jdbc,4,1,1,1,"B1","1","2026-09-01 00:00:00",112,30,"DOC-1");
        jdbc.update("INSERT INTO mes_wm_product_sales VALUES(20,1,'DOC-1',4,0,'PRIVATE CLIENT',888)");
        jdbc.update("INSERT INTO mes_wm_transfer VALUES(30,1,'DOC-1',4,0,'PRIVATE CLIENT',999)");
        var first=assistant.ask(new Question("查销售出库DOC-1的库存流水",false));
        assertEquals("CLARIFY",first.get("status")); assertEquals("DOCUMENT_AMBIGUOUS",object(first,"clarification").get("reason"));
        assertEquals(2,list(object(first,"clarification"),"choices").size());
        var sale=select(first,"销售出库"); var evidence=object(sale,"evidence");
        assertEquals("ANSWERED",sale.get("status")); assertEquals(118,object(evidence,"document").get("bizType")); assertEquals(1,list(evidence,"list").size());
        assertEquals("-2",list(evidence,"list").get(0).get("quantity")); assertEquals("2",list(evidence,"unitGroups").get(0).get("outboundQuantity"));
        assertEquals(true,object(object(evidence,"document"),"source").get("canOpen")); assertFalse(evidence.toString().contains("PRIVATE CLIENT"));
        var another=assistant.ask(new Question("查调拨单DOC-1的库存流水",false)); var transfer=select(another,"调拨"); var transferEvidence=object(transfer,"evidence");
        assertEquals(111,object(transferEvidence,"document").get("bizType")); assertEquals(2,list(transferEvidence,"list").size());
        assertEquals(Set.of(111,112),new HashSet<>(list(transferEvidence,"list").stream().map(row->object(row,"source").get("bizType")).toList()));
        assertEquals("0",list(transferEvidence,"unitGroups").get(0).get("netQuantity"));
        var page=store.evidence(id(transfer,"turnId"),1,20); assertEquals(transferEvidence.get("asOf"),page.get("asOf")); assertEquals(2,list(page,"list").size());
        // The selected header fact is protected in storage as well as in the initial answer.
        when(security.hasPermission("mes:wm-product-sales:query")).thenReturn(false);
        assertThrows(ServiceException.class,()->store.get(id(sale,"conversationId"),null,20));
        assertThrows(ServiceException.class,()->store.evidence(id(sale,"turnId"),1,20)); verifyNoInteractions(legacy);
    }
    @Test void explicitFollowupNewDocumentReplacesPreviouslySelectedCodeAndSourceIdentity() {
        stock(jdbc,1,1,1,"B1","3","2026-08-01 00:00:00");
        tx(jdbc,1,1,1,1,"B1","10","2026-08-01 00:00:00",110,10,"RECEIPT");
        tx(jdbc,2,1,1,1,"B1","-2","2026-09-01 00:00:00",118,20,"DOC-1");
        tx(jdbc,3,1,1,1,"B1","-1","2026-09-01 00:00:00",111,30,"DOC-1");
        tx(jdbc,4,1,1,1,"B1","1","2026-09-01 00:00:00",112,30,"DOC-1");
        tx(jdbc,5,1,1,1,"B1","-5","2026-09-02 00:00:00",118,21,"DOC-2");
        jdbc.update("INSERT INTO mes_wm_product_sales VALUES(20,1,'DOC-1',4,0,'PRIVATE CLIENT',888),(21,1,'DOC-2',4,0,'PRIVATE CLIENT',999)");
        jdbc.update("INSERT INTO mes_wm_transfer VALUES(30,1,'DOC-1',4,0,'PRIVATE CLIENT',999)");
        var first=assistant.ask(new Question("查询单据DOC-1",false));assertEquals("CLARIFY",first.get("status"));
        var selected=select(first,"销售出库");assertEquals(20L,id(object(object(selected,"evidence"),"document"),"bizId"));
        var follow=assistant.ask(next(selected,"那查单据DOC-2"));assertEquals("ANSWERED",follow.get("status"));
        var evidence=object(follow,"evidence");assertEquals("DOC-2",object(evidence,"document").get("bizCode"));assertEquals(21L,id(object(evidence,"document"),"bizId"));
        assertEquals(1,list(evidence,"list").size());assertEquals("-5",list(evidence,"list").get(0).get("quantity"));assertEquals("5",list(evidence,"unitGroups").get(0).get("outboundQuantity"));
        var old=store.evidence(id(selected,"turnId"),1,20);assertEquals("DOC-1",object(old,"document").get("bizCode"));assertEquals("-2",list(old,"list").get(0).get("quantity"));
        assertEquals(3,follow.get("version"));verifyNoInteractions(legacy);
    }
}
