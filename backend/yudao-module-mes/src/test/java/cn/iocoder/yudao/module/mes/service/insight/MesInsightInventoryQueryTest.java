package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightInventoryFixture.*;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesInsightInventoryQueryTest {
    JdbcTemplate jdbc; SecurityFrameworkService security; MesInsightInventoryQuery query;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"),ZoneId.of("Asia/Shanghai"));
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:inventory_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=spy(new JdbcTemplate(ds)); create(jdbc,false);
        security=mock(SecurityFrameworkService.class); when(security.hasPermission(MesInsightInventoryQuery.STOCK_PERMISSION)).thenReturn(true);
        query=new MesInsightInventoryQuery(jdbc,security,new DataSourceTransactionManager(ds),clock); TenantContextHolder.setTenantId(1L);
        clearInvocations(jdbc);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); jdbc.execute("DROP ALL OBJECTS"); }
    Map<String,Object> inventory(int year,int month) { return query.inventory(map("year",year,"month",month)); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> m) { return (List<Map<String,Object>>)m.get("list"); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> totals(Map<String,Object> m) { return (List<Map<String,Object>>)m.get("unitGroups"); }
    @SuppressWarnings("unchecked") Map<String,Object> object(Map<String,Object> m,String key) { return (Map<String,Object>)m.get(key); }
    void normal() { stock(jdbc,1,1,1,"B1","10","2026-08-01 00:00:00"); tx(jdbc,1,1,1,1,"B1","10","2026-08-01 00:00:00",110,10,"RK-1"); }
    @Test void permissionIsCheckedBeforeAnySnapshotOrBusinessRead() {
        when(security.hasPermission(MesInsightInventoryQuery.STOCK_PERMISSION)).thenReturn(false);
        assertThrows(ServiceException.class,()->inventory(2026,9)); verifyNoInteractions(jdbc);
        assertThrows(ServiceException.class,()->query.document(map("documentCode","X"))); verifyNoInteractions(jdbc);
    }
    @Test void halfOpenLeapMonthAndLaterMovementsUseExactDecimal() {
        stock(jdbc,1,1,1,"B1","10.000001","2024-01-01 00:00:00");
        tx(jdbc,1,1,1,1,"B1","5","2024-01-31 23:59:59",110,10,"A");
        tx(jdbc,2,1,1,1,"B1","2.000001","2024-02-01 00:00:00",110,11,"B");
        tx(jdbc,3,1,1,1,"B1","-1","2024-02-29 23:59:59",118,12,"C");
        tx(jdbc,4,1,1,1,"B1","4","2024-03-01 00:00:00",110,13,"D");
        var evidence=inventory(2024,2); var row=rows(evidence).get(0);
        assertEquals("5",row.get("openingQuantity")); assertEquals("2.000001",row.get("inboundQuantity"));
        assertEquals("1",row.get("outboundQuantity")); assertEquals("6.000001",row.get("closingQuantity"));
        assertEquals(true,row.get("balanced")); assertEquals(true,evidence.get("complete"));
        assertEquals("2024-03-01T00:00+08:00",object(evidence,"period").get("endExclusive"));
    }
    @Test void importedOpeningBeforeMonthIsPreservedAndDisclosed() {
        stock(jdbc,1,1,1,"B1","12","2026-01-01 00:00:00"); tx(jdbc,1,1,1,1,"B1","2","2026-09-02 00:00:00",110,10,"A");
        var e=inventory(2026,9); assertEquals("10",rows(e).get(0).get("openingQuantity")); assertEquals("12",rows(e).get(0).get("closingQuantity"));
        assertTrue(e.get("warnings").toString().contains("IMPORTED_BALANCE"));
    }
    @Test void importedStockDoesNotInventPhysicalBalancesBeforeCreation() {
        stock(jdbc,1,1,1,"B1","100","2026-10-01 00:00:00");
        var row=rows(inventory(2026,8)).get(0); assertNull(row.get("openingQuantity")); assertNull(row.get("closingQuantity"));
        assertNull(row.get("balanced")); assertEquals(false,row.get("complete"));
        var october=rows(inventory(2026,10)).get(0); assertEquals("100",october.get("openingQuantity"));
    }
    @Test void ordinaryFirstReceiptAfterHistoricalMonthProvesZeroBackCalculation() {
        normal(); var row=rows(inventory(2026,7)).get(0); assertEquals("0",row.get("openingQuantity")); assertEquals("0",row.get("closingQuantity")); assertEquals(true,row.get("complete"));
    }
    @Test void internalTransfersIncludeFrozenWipAndQuarantineAndNetToZero() {
        stock(jdbc,1,1,1,"B1","5","2026-08-01 00:00:00"); stock(jdbc,2,1,2,"B1","3","2026-09-01 00:00:00"); stock(jdbc,3,1,3,"B1","2","2026-09-02 00:00:00");
        jdbc.update("UPDATE mes_wm_material_stock SET batch_id=1,frozen=1,quality_hold=1");
        tx(jdbc,1,1,1,1,"B1","10","2026-08-01 00:00:00",110,10,"A");
        tx(jdbc,2,1,1,1,"B1","-3","2026-09-01 00:00:00",111,20,"T1"); tx(jdbc,3,2,1,2,"B1","3","2026-09-01 00:00:00",112,20,"T1");
        tx(jdbc,4,1,1,1,"B1","-2","2026-09-02 00:00:00",111,21,"T2"); tx(jdbc,5,3,1,3,"B1","2","2026-09-02 00:00:00",112,21,"T2");
        jdbc.update("UPDATE mes_wm_transaction SET batch_id=1");
        var e=query.inventory(map("year",2026,"month",9,"groupBy","ITEM")); var r=rows(e).get(0);
        assertEquals("10",r.get("openingQuantity")); assertEquals("5",r.get("inboundQuantity")); assertEquals("5",r.get("outboundQuantity")); assertEquals("10",r.get("closingQuantity")); assertEquals(true,r.get("complete"));
        assertEquals("ALL_PHYSICAL",object(e,"filters").get("stockScope"));
        var scoped=query.inventory(map("year",2026,"month",9,"warehouseId",3L,"groupBy","WAREHOUSE"));
        assertEquals("2",rows(scoped).get(0).get("closingQuantity"));
    }
    @Test void categorySubtreeCurrentUnitGroupsAndTenantIsolation() {
        normal(); stock(jdbc,2,2,1,"B2","1.123456","2026-08-01 00:00:00"); tx(jdbc,2,2,2,1,"B2","1.123456","2026-08-01 00:00:00",110,20,"B");
        stock(jdbc,3,3,1,"B3","50","2026-08-01 00:00:00"); jdbc.update("INSERT INTO mes_wm_material_stock(id,tenant_id,item_id,quantity,deleted) VALUES(4,2,4,999,0)");
        var e=query.inventory(map("year",2026,"month",9,"categoryId",1L)); assertEquals(2,rows(e).size()); assertEquals(2,totals(e).size());
        assertEquals("10",totals(e).get(0).get("closingQuantity")); assertEquals("1.123456",totals(e).get(1).get("closingQuantity"));
        assertThrows(ServiceException.class,()->query.inventory(map("year",2026,"month",9,"itemId",4L)));
        assertThrows(ServiceException.class,()->query.inventory(map("year",2026,"month",9,"warehouseId",4L)));
    }
    @Test void missingUnitsStayPerItemAndDoNotCrossSum() {
        jdbc.update("UPDATE mes_md_item SET unit_measure_id=NULL WHERE id IN(1,2)");
        stock(jdbc,1,1,1,"B1","10","2026-08-01 00:00:00"); stock(jdbc,2,2,1,"B2","20","2026-08-01 00:00:00");
        var e=inventory(2026,9); assertEquals(2,totals(e).size()); assertEquals("UNKNOWN_ITEM:1",totals(e).get(0).get("unitKey")); assertEquals(false,e.get("complete"));
    }
    @Test void orphanLedgerKeepsKnownMovementsAndUnknownBalances() {
        tx(jdbc,1,999,1,1,"X","2","2026-09-01 00:00:00",110,10,"A");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("openingQuantity")); assertNull(row.get("closingQuantity")); assertEquals("2",row.get("inboundQuantity")); assertEquals(false,row.get("complete"));
    }
    @Test void missingTransactionTimeMakesMonthAssignmentUnknown() {
        normal(); jdbc.update("UPDATE mes_wm_transaction SET transaction_time=NULL");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("inboundQuantity")); assertNull(row.get("outboundQuantity")); assertNull(row.get("closingQuantity"));
    }
    @Test void missingCurrentQuantityDoesNotBecomeZeroBalance() {
        normal(); jdbc.update("UPDATE mes_wm_material_stock SET quantity=NULL");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("openingQuantity")); assertNull(row.get("closingQuantity"));
        assertEquals(false,row.get("complete")); assertTrue(row.get("warnings").toString().contains("MISSING_STOCK_QUANTITY"));
    }
    @Test void missingTransactionQuantityDoesNotBecomeZeroMovement() {
        normal(); jdbc.update("UPDATE mes_wm_transaction SET quantity=NULL");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("inboundQuantity")); assertNull(row.get("outboundQuantity")); assertNull(row.get("openingQuantity"));
        assertTrue(row.get("warnings").toString().contains("MISSING_TRANSACTION_QUANTITY"));
        assertThrows(ServiceException.class,()->query.document(map("documentCode","RK-1")));
    }
    @Test void futureTimeAlreadyAffectingBalanceIsNotSilentlySubtracted() {
        normal(); jdbc.update("UPDATE mes_wm_transaction SET transaction_time='2026-11-01 00:00:00'");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("openingQuantity")); assertEquals(false,row.get("complete")); assertTrue(row.get("warnings").toString().contains("FUTURE_TRANSACTION_TIME"));
    }
    @Test void deletedStockAndDeletedLedgerCannotPretendComplete() {
        normal(); jdbc.update("UPDATE mes_wm_material_stock SET deleted=1");
        assertNull(rows(inventory(2026,9)).get(0).get("closingQuantity"));
        jdbc.update("UPDATE mes_wm_material_stock SET deleted=0"); jdbc.update("UPDATE mes_wm_transaction SET deleted=1");
        var row=rows(inventory(2026,9)).get(0); assertNull(row.get("closingQuantity")); assertTrue(row.get("warnings").toString().contains("DELETED_TRANSACTION"));
    }
    @Test void dimensionMismatchProducesUnknownEvidenceForBothIdentities() {
        normal(); jdbc.update("UPDATE mes_wm_transaction SET batch_code='WRONG'");
        var e=inventory(2026,9); assertEquals(2,rows(e).size()); assertTrue(rows(e).stream().allMatch(r->r.get("closingQuantity")==null));
    }
    @Test void categoryCycleAndInvalidFutureMonthRequireClarification() {
        jdbc.update("UPDATE mes_md_item_type SET parent_id=2 WHERE id=1");
        assertEquals("NEED_FILTER",assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->query.inventory(map("year",2026,"month",9,"categoryId",1L))).getReason());
        assertEquals("INVALID_PERIOD",assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->inventory(2026,11)).getReason());
        assertEquals("INVALID_PERIOD",assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->inventory(2026,13)).getReason());
    }
    @Test void moreThanFiveThousandGroupsClarifiesWithoutPartialFacts() {
        jdbc.execute("INSERT INTO mes_wm_material_stock(id,tenant_id,item_id,batch_id,batch_code,warehouse_id,quantity,create_time,deleted) SELECT n,1,1,n,'B',1,0,'2026-01-01 00:00:00',0 FROM SYSTEM_RANGE(1,5001) AS series(n)");
        assertEquals("SCOPE_TOO_LARGE",assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->inventory(2026,9)).getReason());
    }
    @Test void deepCategoryChainUsesBoundedIterationInsteadOfRecursiveStack() {
        jdbc.execute("INSERT INTO mes_md_item_type(id,tenant_id,code,name,parent_id,deleted) SELECT n+100,1,CONCAT('T',n),'深分类',CASE WHEN n=1 THEN 1 ELSE n+99 END,0 FROM SYSTEM_RANGE(1,2000) AS series(n)");
        jdbc.update("UPDATE mes_md_item SET item_type_id=2100 WHERE id=1"); normal();
        assertEquals(1,rows(query.inventory(map("year",2026,"month",9,"categoryId",1L))).size());
    }
    @Test void fiftyThousandPhysicalPositionsCannotHideBehindOneItemGroup() {
        jdbc.execute("INSERT INTO mes_wm_material_stock(id,tenant_id,item_id,batch_id,batch_code,warehouse_id,quantity,create_time,deleted) SELECT n,1,1,1,'B',1,0,'2026-01-01 00:00:00',0 FROM SYSTEM_RANGE(1,50001) AS series(n)");
        var error=assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->query.inventory(map("year",2026,"month",9,"groupBy","ITEM")));
        assertEquals("SCOPE_TOO_LARGE",error.getReason()); assertTrue(error.getPrompt().contains("50000"));
    }
    @Test void fiftyThousandLedgerPositionsCannotHideBehindOneItemGroup() {
        jdbc.execute("INSERT INTO mes_wm_transaction(id,tenant_id,material_stock_id,item_id,batch_id,batch_code,warehouse_id,quantity,transaction_time,deleted) SELECT n,1,n,1,1,'B',1,1,'2026-09-01 00:00:00',0 FROM SYSTEM_RANGE(1,50001) AS series(n)");
        var error=assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->query.inventory(map("year",2026,"month",9,"groupBy","ITEM")));
        assertEquals("SCOPE_TOO_LARGE",error.getReason()); assertTrue(error.getPrompt().contains("50000"));
    }
    @Test void fullScopeSummaryCoversAllRowsBeforeSnapshotPagination() {
        for(int i=1;i<=26;i++) { stock(jdbc,i,1,1,"B"+i,"0.000001","2026-08-01 00:00:00"); tx(jdbc,i,i,1,1,"B"+i,"0.000001","2026-08-01 00:00:00",110,i,"R"+i); }
        var e=inventory(2026,9); assertEquals(26,e.get("total")); assertEquals(26,rows(e).size()); assertEquals(true,e.get("hasMore")); assertEquals("0.000026",totals(e).get(0).get("closingQuantity"));
    }
    @Test void currentMonthUsesShanghaiAsOfAndExactLiteralBatchFilter() {
        normal(); jdbc.update("UPDATE mes_wm_material_stock SET batch_code=?", "X' OR 1=1 --"); jdbc.update("UPDATE mes_wm_transaction SET batch_code=?","X' OR 1=1 --");
        var e=query.inventory(map("year",2026,"month",10,"batchCode","X' OR 1=1 --"));
        assertEquals(1,rows(e).size()); assertEquals(true,object(e,"period").get("partialMonth")); assertEquals("2026-10-20T12:00+08:00",e.get("asOf"));
        assertEquals(0,rows(query.inventory(map("year",2026,"month",10,"batchCode","%"))).size());
    }
    @Test void stockOnlyDocumentContainsNoPrivateHeaderFactsOrLinks() {
        normal(); jdbc.update("INSERT INTO mes_wm_item_receipt VALUES(10,1,'RK-1',4,0,'SECRET',999)");
        var e=query.document(map("documentCode","RK-1")); var doc=object(e,"document");
        assertEquals(false,doc.get("headerKnown")); assertNull(doc.get("businessStatus")); assertEquals(false,object(doc,"source").get("canOpen"));
        assertFalse(e.toString().contains("SECRET")); assertFalse(e.toString().contains("private_price")); assertEquals("10",totals(e).get(0).get("inboundQuantity"));
    }
    @Test void authorizedDraftHeaderMeansNoPostingRatherThanNonexistent() {
        when(security.hasPermission("mes:wm-product-sales:query")).thenReturn(true);
        jdbc.update("INSERT INTO mes_wm_product_sales VALUES(70,1,'DRAFT',0,0,'SECRET',999)");
        var e=query.document(map("documentCode","DRAFT")); var doc=object(e,"document");
        assertEquals(true,doc.get("headerKnown")); assertEquals(0,doc.get("businessStatus")); assertEquals("NO_POSTED_MOVEMENTS",doc.get("postingStatus"));
        assertTrue(e.get("_requiredPermissions").toString().contains("mes:wm-product-sales:query")); assertFalse(e.toString().contains("SECRET"));
    }
    @Test void duplicateCodesClarifyButRealTransferPairIsOneIdentity() {
        normal(); tx(jdbc,2,1,1,1,"B1","-1","2026-09-01 00:00:00",118,20,"RK-1");
        var ex=assertThrows(MesInsightInventoryQuery.ClarificationException.class,()->query.document(map("documentCode","RK-1")));
        assertEquals("DOCUMENT_AMBIGUOUS",ex.getReason()); assertEquals(2,ex.getChoices().size());
        tx(jdbc,3,1,1,1,"B1","-2","2026-09-01 00:00:00",111,30,"T"); tx(jdbc,4,1,1,1,"B1","2","2026-09-01 00:00:00",112,30,"T");
        var e=query.document(map("documentCode","T")); assertEquals(2,rows(e).size()); assertEquals("0",totals(e).get(0).get("netQuantity")); assertEquals(111,object(e,"document").get("bizType"));
    }
    @Test void documentSourcePermissionsAreRefreshedAndUnsupportedTypesStayPlain() {
        normal(); when(security.hasPermission("mes:wm-item-receipt:query")).thenReturn(true);
        var e=query.document(map("documentCode","RK-1")); assertEquals(true,object(object(e,"document"),"source").get("canOpen"));
        when(security.hasPermission("mes:wm-item-receipt:query")).thenReturn(false); query.refreshSourcePermissions(e);
        assertEquals(false,object(object(e,"document"),"source").get("canOpen"));
        tx(jdbc,2,1,1,1,"B1","-1","2026-09-01 00:00:00",122,90,"CONSUME");
        when(security.hasPermission("mes:pro-feedback:query")).thenReturn(true);
        assertEquals(false,object(object(query.document(map("documentCode","CONSUME")),"document"),"source").get("canOpen"));
    }
    @Test void exactDocumentLookupCannotInjectOrUseOtherTenantHeader() {
        normal(); when(security.hasPermission("mes:wm-product-sales:query")).thenReturn(true);
        jdbc.update("INSERT INTO mes_wm_product_sales VALUES(70,2,'PRIVATE',4,0,'SECRET',999)");
        assertEquals(0,query.document(map("documentCode","RK-1' OR 1=1 --")).get("total"));
        assertEquals(false,object(query.document(map("documentCode","PRIVATE")),"document").get("headerKnown"));
    }
}
