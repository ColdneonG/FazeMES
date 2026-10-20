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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesInsightBusinessAssistantTest {
    JdbcTemplate jdbc;SecurityFrameworkService security;MesInsightInventoryQuery inventory;MesInsightService legacy;
    MesInsightConversationStore store;MesInsightBusinessAssistant assistant;DataSourceTransactionManager manager;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:business_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/insight_business.sql")).execute(source);jdbc=spy(new JdbcTemplate(source));manager=new DataSourceTransactionManager(source);
        for(String table:List.of("mes_md_item","mes_md_item_type","mes_wm_warehouse"))jdbc.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(128),name VARCHAR(255),deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO mes_md_item_type VALUES(10,1,'RM','原材料',0),(20,2,'PRIVATE','秘密分类',0)");
        jdbc.update("INSERT INTO mes_md_item VALUES(100,1,'BROKY-RM001','铝片',0),(200,2,'PRIVATE','秘密产品',0)");
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(1,1,'RAW','原料仓',0),(2,1,'FG','成品仓',0)");
        security=mock(SecurityFrameworkService.class);when(security.hasPermission(anyString())).thenReturn(true);
        inventory=mock(MesInsightInventoryQuery.class);legacy=mock(MesInsightService.class);
        when(inventory.inventory(anyMap())).thenAnswer(call->evidence(25));when(inventory.document(anyMap())).thenAnswer(call->map("kind","STOCK_DOCUMENT","list",List.of(),"total",0,"unitGroups",List.of(),"warnings",List.of()));
        store=new MesInsightConversationStore(jdbc,new ObjectMapper(),security,manager,inventory);assistant=new MesInsightBusinessAssistant(jdbc,security,store,inventory,legacy);login(1,10);
    }
    private static Map<String,Object> evidence(int count){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<count;i++)rows.add(map("rowKey","row"+i,"openingQuantity","0.000001","closingQuantity","1.000001"));return map("kind","INVENTORY_LEDGER","asOf","2026-10-20T12:00:00+08:00","list",rows,"total",count,"unitGroups",List.of(map("unitKey","UNIT:1","closingQuantity","25.000025")),"warnings",List.of(),"period",map("year",2026,"month",9));}
    private static void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    private Question ask(String q){return new Question(q,false);}
    private Question next(Map<String,Object> a,String q){return new Question(q,false,((Number)a.get("conversationId")).longValue(),((Number)a.get("version")).intValue(),null);}
    @SuppressWarnings("unchecked") private Map<String,Object> evidenceOf(Map<String,Object> answer){return (Map<String,Object>)answer.get("evidence");}
    @Test void realParserPersistsIndependentConversationAndSnapshotPager(){
        var answer=assistant.ask(ask("查2026年9月原材料库存台账，按批次"));
        assertEquals("inventory",answer.get("topic"));assertEquals("ANSWERED",answer.get("status"));assertEquals(1,answer.get("version"));
        assertEquals(20,((List<?>)evidenceOf(answer).get("list")).size());assertEquals(25,evidenceOf(answer).get("total"));
        long id=((Number)answer.get("conversationId")).longValue(),turn=((Number)answer.get("turnId")).longValue();
        var reloaded=store.get(id,null,20);assertEquals(1,((List<?>)reloaded.get("turns")).size());
        var page=store.evidence(turn,2,20);assertEquals(5,((List<?>)page.get("list")).size());assertEquals(evidenceOf(answer).get("asOf"),page.get("asOf"));assertEquals(evidenceOf(answer).get("unitGroups"),page.get("unitGroups"));
        verify(inventory,times(1)).inventory(argThat(slots->slots.get("year").equals(2026)&&slots.get("month").equals(9)&&((Number)slots.get("categoryId")).longValue()==10));
        verifyNoInteractions(legacy);
    }
    @Test void chineseMonthYearAndConversationFollowupRetainConfirmedYearAndCategory(){
        var answer=assistant.ask(ask("二〇二六年九月原材料库存，按批次"));
        var second=assistant.ask(next(answer,"那8月份，只看原料仓"));
        assistant.ask(next(second,"按仓库"));
        verify(inventory).inventory(argThat(slots->slots.get("month").equals(8)&&slots.get("groupBy").equals("WAREHOUSE")&&((Number)slots.get("warehouseId")).longValue()==1&&((Number)slots.get("categoryId")).longValue()==10&&slots.get("year").equals(2026)));
    }
    @Test void missingYearClarifiesThenOpaqueChoiceAddsOneTurnAndCannotBeReplayed(){
        var first=assistant.ask(ask("9月份原材料库存"));assertEquals("CLARIFY",first.get("status"));verify(inventory,never()).inventory(anyMap());
        @SuppressWarnings("unchecked") var clarification=(Map<String,Object>)first.get("clarification");
        @SuppressWarnings("unchecked") var choice=(Map<String,Object>)((List<?>)clarification.get("choices")).get(0);
        var req=new Question(null,false,((Number)first.get("conversationId")).longValue(),1,new ClarificationChoice(clarification.get("token").toString(),choice.get("id").toString()));
        var second=assistant.ask(req);assertEquals("ANSWERED",second.get("status"));assertEquals(2,second.get("version"));
        assertThrows(ServiceException.class,()->assistant.ask(req));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class));
    }
    @Test void bareYearFreeTextResolvesPendingWithoutCurrentYearGuess(){
        var first=assistant.ask(ask("9月份原材料库存"));var second=assistant.ask(next(first,"2025"));assertEquals("ANSWERED",second.get("status"));
        verify(inventory).inventory(argThat(slots->slots.get("year").equals(2025)&&slots.get("month").equals(9)));
    }
    @Test void staleVersionAndForeignTokenDoNotReadFactsOrAppend(){
        var first=assistant.ask(ask("查2026年9月库存"));clearInvocations(inventory);
        assertThrows(ServiceException.class,()->assistant.ask(new Question("按仓库",true,((Number)first.get("conversationId")).longValue(),0,null)));
        assertThrows(ServiceException.class,()->assistant.ask(new Question(null,true,((Number)first.get("conversationId")).longValue(),1,new ClarificationChoice("forged","1"))));
        verifyNoInteractions(inventory,legacy);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class));
    }
    @Test void revokedStockPermissionHidesTitlesCountsHistoryEvidenceAndContextBeforeData(){
        var first=assistant.ask(ask("查2026年9月库存"));long id=((Number)first.get("conversationId")).longValue(),turn=((Number)first.get("turnId")).longValue();
        when(security.hasPermission(MesInsightInventoryQuery.STOCK_PERMISSION)).thenReturn(false);clearInvocations(inventory);
        assertEquals(0,store.page(1,20).get("total"));assertThrows(ServiceException.class,()->store.get(id,null,20));assertThrows(ServiceException.class,()->store.evidence(turn,1,20));assertThrows(ServiceException.class,()->assistant.ask(next(first,"按仓库")));verifyNoInteractions(inventory,legacy);
        assertEquals(0,store.create(null).get("version"));
    }
    @Test void ownerTenantIsolationAppliesToHistoryEvidenceAndDelete(){
        var first=assistant.ask(ask("查2026年9月库存"));long id=((Number)first.get("conversationId")).longValue(),turn=((Number)first.get("turnId")).longValue();
        for(long[] identity:List.of(new long[]{1,11},new long[]{2,10})){login(identity[0],identity[1]);assertEquals(0,store.page(1,20).get("total"));assertThrows(ServiceException.class,()->store.get(id,null,20));assertThrows(ServiceException.class,()->store.evidence(turn,1,20));assertThrows(ServiceException.class,()->store.delete(id,1));}
    }
    @Test void unrelatedWritesAvailabilityAndValuationNeverFallBackToRisksOrPhysicalTotals(){
        for(String q:List.of("推荐旅游地点","帮我把库存改成100","查2026年9月可用库存","查2026年9月库存金额")){var a=assistant.ask(ask(q));assertEquals("OUT_OF_SCOPE",a.get("status"));assertEquals("scope",a.get("topic"));}
        verify(inventory,never()).inventory(anyMap());verify(inventory,never()).document(anyMap());verifyNoInteractions(legacy);
    }
    @Test void useModelForStockRemainsRulesAndNoHttpOrLegacyPath(){
        var a=assistant.ask(new Question("查2026年9月库存",true));assertEquals("RULES",a.get("mode"));assertTrue(evidenceOf(a).get("warnings").toString().contains("MODEL_NOT_USED"));verifyNoInteractions(legacy);
    }
    @Test void duplicateEntityNameClarifiesAndSelectionIsServerOwned(){
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(3,1,'RAW2','原料仓',0)");
        var first=assistant.ask(ask("查2026年9月原料仓库存"));assertEquals("CLARIFY",first.get("status"));verify(inventory,never()).inventory(anyMap());
        @SuppressWarnings("unchecked") var c=(Map<String,Object>)first.get("clarification");@SuppressWarnings("unchecked") var choice=(Map<String,Object>)((List<?>)c.get("choices")).get(1);
        var second=assistant.ask(new Question(null,false,((Number)first.get("conversationId")).longValue(),1,new ClarificationChoice(c.get("token").toString(),choice.get("id").toString())));assertEquals("ANSWERED",second.get("status"));verify(inventory).inventory(argThat(slots->((Number)slots.get("warehouseId")).longValue()==3));
    }
    @Test void quotePercentUnderscoreAndForeignCodeCannotChangeBoundQueryScope(){
        var answer=assistant.ask(ask("查2026年9月PRIVATE' OR 1=1 --库存"));assertEquals("CLARIFY",answer.get("status"));verify(inventory,never()).inventory(anyMap());
    }
    @Test void relativeMonthAndFutureMonthAreExplicit(){
        var previous=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusMonths(1);assistant.ask(ask("上个月原料仓收发存"));verify(inventory).inventory(argThat(slots->slots.get("year").equals(previous.getYear())&&slots.get("month").equals(previous.getMonthValue())));
        var future=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusYears(1);var answer=assistant.ask(ask("查"+future.getYear()+"年12月库存"));assertEquals("CLARIFY",answer.get("status"));assertTrue(answer.get("clarification").toString().contains("INVALID_PERIOD"));
    }
    @Test void deleteUsesExactVersionAndRemovesAllOwnedEvidence(){
        var a=assistant.ask(ask("查2026年9月库存"));long id=((Number)a.get("conversationId")).longValue();assertThrows(ServiceException.class,()->store.delete(id,0));store.delete(id,1);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_conversation",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_evidence",Integer.class));
    }
    @Test void concurrentSameVersionClaimRunsOnlyOneQueryAndDeleteCannotOvertake()throws Exception{
        long id=((Number)store.create(null).get("id")).longValue();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger queries=new AtomicInteger();
        when(inventory.inventory(anyMap())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());queries.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return evidence(1);});
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->{login(1,10);try{return assistant.ask(new Question("查2026年9月库存",false,id,0,null));}finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(ServiceException.class,()->assistant.ask(new Question("查2026年9月库存",false,id,0,null)));assertThrows(ServiceException.class,()->store.delete(id,0));release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS).get("version"));assertEquals(1,queries.get());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void legacyModelFailureKeepsAlreadyQueriedRulesAndDoesNotHoldConversationLock(){
        when(legacy.ask(any())).thenAnswer(call->{Question q=call.getArgument(0);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(q.useModel())throw new IllegalStateException("secret provider payload");return map("answer","实际成本待核定","mode","RULES","topic","cost","evidence",map("items",List.of(map("totalCost",null))),"suggestions",List.of());});
        var answer=assistant.ask(new Question("BROKY-RM001成本",true));assertEquals("RULES",answer.get("mode"));assertEquals("实际成本待核定",answer.get("answer"));assertFalse(answer.toString().contains("secret"));assertTrue(answer.get("modelWarning").toString().contains("保留"));
    }
    @Test void exactCodeStillWorksWithMoreThanTenThousandMasterRows(){
        jdbc.execute("INSERT INTO mes_md_item(id,tenant_id,code,name,deleted) SELECT 1000+n,1,CONCAT('OTHER-',n),CONCAT('其他',n),0 FROM SYSTEM_RANGE(1,10001) AS series(n)");
        assertEquals("ANSWERED",assistant.ask(ask("查2026年9月BROKY-RM001库存")).get("status"));
        verify(inventory).inventory(argThat(slots->((Number)slots.get("itemId")).longValue()==100));
    }
    @Test void unknownChineseFilterCannotExpandScopeOrKeepPreviouslySelectedWarehouse(){
        for(String q:List.of("查2026年9月火星原料仓库存","查2026年9月不存在合金库存"))assertEquals("CLARIFY",assistant.ask(ask(q)).get("status"));
        var first=assistant.ask(ask("查2026年9月原料仓库存"));clearInvocations(inventory);
        assertEquals("CLARIFY",assistant.ask(next(first,"只看火星仓库")).get("status"));verify(inventory,never()).inventory(anyMap());
    }
    @Test void explicitDocumentIntentDoesNotNeedTheWordLedgerAndCodesStayLiteral(){
        for(String q:List.of("查询单据XS-001","查销售出库单XS-001","查询单号：采购2026_%_A"))assertEquals("document",assistant.ask(ask(q)).get("topic"));
        verify(inventory,times(2)).document(argThat(slots->"XS-001".equals(slots.get("documentCode"))));
        verify(inventory).document(argThat(slots->"采购2026_%_A".equals(slots.get("documentCode"))));
    }
    @Test void globalWriteRequestsNeverInvokeLegacyFactsOrStockTools(){
        for(String q:List.of("删除工单WO-1","修改BROKY-RM001价格","帮我执行出库单XS-001"))assertEquals("OUT_OF_SCOPE",assistant.ask(ask(q)).get("status"));
        verify(inventory,never()).inventory(anyMap());verify(inventory,never()).document(anyMap());verifyNoInteractions(legacy);
    }
    @Test void batchCodesPreserveChinesePercentUnderscoreAsExactLiteral(){
        assertEquals("ANSWERED",assistant.ask(ask("查2026年9月库存，批次号：中文_批次%_A")).get("status"));
        verify(inventory).inventory(argThat(slots->"中文_批次%_A".equals(slots.get("batchCode"))));
    }
    @Test void freeTextExactCodeResolvesDuplicateName(){
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(3,1,'RAW2','原料仓',0)");var first=assistant.ask(ask("查2026年9月原料仓库存"));
        assertEquals("ANSWERED",assistant.ask(next(first,"RAW2")).get("status"));verify(inventory).inventory(argThat(slots->((Number)slots.get("warehouseId")).longValue()==3));
    }
    @Test void historyCursorKeepsCurrentVersionAndSnapshotPagingDoesNotCreateTurns(){
        var one=assistant.ask(ask("查2026年9月库存"));var two=assistant.ask(next(one,"按仓库"));var three=assistant.ask(next(two,"按物料"));long id=((Number)one.get("conversationId")).longValue();
        var recent=store.get(id,null,2);assertEquals(true,recent.get("hasMore"));assertEquals(3,((Map<?,?>)recent.get("conversation")).get("version"));assertEquals(2,((List<?>)recent.get("turns")).size());
        var older=store.get(id,((Number)recent.get("nextBeforeTurnId")).longValue(),2);assertEquals(false,older.get("hasMore"));assertEquals(1,((List<?>)older.get("turns")).size());assertEquals(3,((Map<?,?>)older.get("conversation")).get("version"));
        store.evidence(((Number)three.get("turnId")).longValue(),2,20);assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class));
    }
    @Test void documentClarificationAccumulatesHeaderPermissionBeforeLabelsCanBeReloaded(){
        String permission="mes:wm-product-sales:query";
        when(inventory.document(anyMap())).thenThrow(new MesInsightInventoryQuery.ClarificationException("DOCUMENT_AMBIGUOUS","选择单据",List.of(map("label","销售单私有状态","slots",map("documentBizType",118,"documentBizId",7))),List.of(MesInsightInventoryQuery.STOCK_PERMISSION,permission)));
        var first=assistant.ask(ask("查询单据XS-001"));assertEquals("CLARIFY",first.get("status"));when(security.hasPermission(permission)).thenReturn(false);clearInvocations(inventory);
        assertEquals(0,store.page(1,20).get("total"));assertThrows(ServiceException.class,()->store.get(((Number)first.get("conversationId")).longValue(),null,20));assertThrows(ServiceException.class,()->assistant.ask(next(first,"继续")));verifyNoInteractions(inventory);
    }
    @Test void expiredClarificationTokenCannotReadFactsOrAppend()throws Exception{
        var first=assistant.ask(ask("9月份库存"));long id=((Number)first.get("conversationId")).longValue();
        var mapper=new ObjectMapper();@SuppressWarnings("unchecked") var pending=mapper.readValue(jdbc.queryForObject("SELECT pending_json FROM mes_insight_conversation WHERE id=?",String.class,id),Map.class);pending.put("expiresAt","2000-01-01T00:00:00+08:00");jdbc.update("UPDATE mes_insight_conversation SET pending_json=? WHERE id=?",mapper.writeValueAsString(pending),id);
        @SuppressWarnings("unchecked") var clarification=(Map<String,Object>)first.get("clarification");@SuppressWarnings("unchecked") var choice=(Map<String,Object>)((List<?>)clarification.get("choices")).get(0);
        assertThrows(ServiceException.class,()->assistant.ask(new Question(null,true,id,1,new ClarificationChoice(clarification.get("token").toString(),choice.get("id").toString()))));verify(inventory,never()).inventory(anyMap());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_turn",Integer.class));
    }
    @Test void sameNameAcrossItemAndCategoryRequiresAnExplicitChoice(){
        jdbc.update("INSERT INTO mes_md_item VALUES(101,1,'DUPLICATE','原材料',0)");var first=assistant.ask(ask("查2026年9月原材料库存"));assertEquals("CLARIFY",first.get("status"));
        @SuppressWarnings("unchecked") var c=(Map<String,Object>)first.get("clarification");@SuppressWarnings("unchecked") var choice=(Map<String,Object>)((List<?>)c.get("choices")).get(0);
        assertEquals("ANSWERED",assistant.ask(new Question(null,false,((Number)first.get("conversationId")).longValue(),1,new ClarificationChoice(c.get("token").toString(),choice.get("id").toString()))).get("status"));
        verify(inventory).inventory(argThat(slots->((Number)slots.get("categoryId")).longValue()==10&&slots.get("itemId")==null));
    }
    @Test void changingMonthOrWarehouseChangesReadFiltersWithoutBecomingAWrite(){
        var first=assistant.ask(ask("查2026年9月原料仓库存"));var second=assistant.ask(next(first,"换成成品仓"));assertEquals("ANSWERED",second.get("status"));
        var third=assistant.ask(next(second,"改为8月份"));assertEquals("ANSWERED",third.get("status"));verify(inventory).inventory(argThat(slots->slots.get("month").equals(8)&&((Number)slots.get("warehouseId")).longValue()==2));
    }
    @Test void documentChinesePrefixesAndMetacharactersAreNeverDropped(){
        for(String q:List.of("查询单据采购2026_%_A","查销售出库单采购2026_%_A的库存流水"))assertEquals("ANSWERED",assistant.ask(ask(q)).get("status"));
        verify(inventory,times(2)).document(argThat(slots->"采购2026_%_A".equals(slots.get("documentCode"))));
    }
    @Test void explicitNewDocumentCodeReplacesOldIdentityAndUnsupportedGroupingClarifies(){
        var first=assistant.ask(ask("查询单据DOC-1"));
        long id=((Number)first.get("conversationId")).longValue();jdbc.update("UPDATE mes_insight_conversation SET context_json=? WHERE id=?","{\"intent\":\"document\",\"documentCode\":\"DOC-1\",\"documentBizType\":118,\"documentBizId\":20}",id);
        var second=assistant.ask(next(first,"那查单据DOC-2"));assertEquals("ANSWERED",second.get("status"));
        verify(inventory).document(argThat(slots->"DOC-2".equals(slots.get("documentCode"))&&!slots.containsKey("documentBizType")&&!slots.containsKey("documentBizId")));
        clearInvocations(inventory);assertEquals("CLARIFY",assistant.ask(next(second,"那按仓库")).get("status"));verify(inventory,never()).document(anyMap());
    }
}
