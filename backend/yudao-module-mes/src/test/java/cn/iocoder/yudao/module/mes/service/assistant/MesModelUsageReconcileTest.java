package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.vo.MesModelUsageVO.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MesModelUsageReconcileTest extends MesAssistantTestDatabase {
    private static final class MutableClock extends Clock { Instant instant=Instant.parse("2026-10-20T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}void advance(){instant=instant.plusSeconds(180);} }
    private final MutableClock clock=new MutableClock();
    private MesModelUsageService meter;
    @BeforeEach void usage(){meter=new MesModelUsageService(jdbc,json,new DataSourceTransactionManager(jdbc.getDataSource()),clock);}
    private MesModelUsageService.Reservation start(){var r=meter.reserve(MesModelUsageService.Purpose.KNOWLEDGE_CHAT,"CHAT","openai","test-model",100,100);meter.dispatched(r.id());return r;}
    @SuppressWarnings("unchecked") private Map<String,Object> row(){return ((List<Map<String,Object>>)meter.page(new UsagePageRequest(null,null,null,null,null,1,100)).get("list")).get(0);}
    @SuppressWarnings("unchecked") private Map<String,Object> token(){return (Map<String,Object>)((Map<String,Object>)((Map<String,Object>)meter.summary().get("tenant")).get("day")).get("tokens");}
    private int version(){return ((Number)row().get("version")).intValue();}
    private void price(){var l=new Limits("100","1000","1000000","10000000","100","100",10,100);meter.savePolicy(new PolicySaveRequest(1,true,l,l,List.of(new PriceCard("CHAT","openai","test-model","1000","2000",null)),"明确核销测试费率"));}
    @Test void closingAStaleReservationKeepsUnknownExposureAndDoesNotClaimActualConfirmation(){
        price();var r=start();assertThrows(ServiceException.class,()->meter.reconcile(new ReconcileRequest(r.id(),version(),"CLOSE_AS_UNKNOWN",null,null,null,"尚在网络期限内不能关闭")));clock.advance();
        var closed=meter.reconcile(new ReconcileRequest(r.id(),version(),"CLOSE_AS_UNKNOWN",null,null,null,"超时后关闭并保留全部预留"));assertEquals("RESERVATION_HELD",closed.get("usageBasis"));assertNull(closed.get("actualTotalTokens"));assertEquals("200",closed.get("unknownHeldTokens"));assertEquals("200",token().get("unknown"));assertEquals("0",token().get("reserved"));
        meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",10L,20L,30L,"served",200,1,true,false));assertEquals("30",token().get("used"));assertEquals("0",token().get("unknown"));assertEquals("0.05000000",row().get("accountedAmount"));
    }
    @Test void closingOnlyUnknownMoneyDoesNotInventUnknownTokensForKnownUsage(){
        var r=start();meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",2L,3L,5L,"served",200,1,true,false));clock.advance();var closed=meter.reconcile(new ReconcileRequest(r.id(),version(),"CLOSE_AS_UNKNOWN",null,null,null,"服务返回了token但没有已配置费率"));assertEquals("UNPRICED",closed.get("usageBasis"));assertNull(closed.get("unknownHeldTokens"));assertEquals("5",closed.get("actualTotalTokens"));assertEquals("5",token().get("used"));assertEquals("0",token().get("unknown"));
    }
    @Test void lateRealUsageCannotBeHiddenBySmallerEarlierManualEvidence(){
        price();var r=start();clock.advance();meter.reconcile(new ReconcileRequest(r.id(),version(),"CONFIRM_ACTUAL","1","1","0.00100000","人工初核证据，原报告尚未返回"));
        meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",10L,20L,30L,"served",200,5,true,false));var row=row();assertEquals("10",row.get("actualInputTokens"));assertEquals("20",row.get("actualOutputTokens"));assertEquals("1",row.get("confirmedInputTokens"));assertEquals("1",row.get("confirmedOutputTokens"));assertEquals("30",row.get("accountedTokens"));assertEquals("30",token().get("used"));assertEquals("0.05000000",row.get("accountedAmount"));assertEquals("0.00100000",row.get("confirmedAmount"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event WHERE event_type='RECONCILE'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_event WHERE event_type='SETTLE'",Integer.class));
    }
    @Test void staleVersionAndOverflowReconciliationCannotAlterCountersOrEvidence(){
        var r=start();clock.advance();int v=version();assertThrows(ServiceException.class,()->meter.reconcile(new ReconcileRequest(r.id(),v,"CONFIRM_ACTUAL",Long.toString(Long.MAX_VALUE),"1",null,"超出单次有符号整数范围")));assertEquals(v,version());assertEquals("200",token().get("reserved"));
        meter.reconcile(new ReconcileRequest(r.id(),v,"CLOSE_AS_UNKNOWN",null,null,null,"第一位管理员关闭"));assertThrows(ServiceException.class,()->meter.reconcile(new ReconcileRequest(r.id(),v,"CONFIRM_NOT_DISPATCHED",null,null,null,"过期版本不能归零")));assertEquals("200",token().get("unknown"));
    }
    @Test void lateMissingUsageCannotRestoreUnknownHoldsAlreadyConfirmedByManualEvidence(){
        price();var r=start();clock.advance();meter.reconcile(new ReconcileRequest(r.id(),version(),"CONFIRM_ACTUAL","1","1","0.00300000","已取得独立账单和token凭据"));
        meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",null,null,null,"served",200,5,true,false));var row=row();assertNull(row.get("actualInputTokens"));assertNull(row.get("actualOutputTokens"));assertNull(row.get("actualTotalTokens"));assertEquals("1",row.get("confirmedInputTokens"));assertEquals("1",row.get("confirmedOutputTokens"));assertNull(row.get("unknownHeldTokens"));assertNull(row.get("unknownHeldAmount"));assertEquals("2",row.get("accountedTokens"));assertEquals("0.00300000",row.get("accountedAmount"));assertEquals("2",token().get("used"));assertEquals("0",token().get("unknown"));
    }
    @Test void totalWithoutInputOutputEvidenceIsRejectedWithoutChangingAnAdmittedCall(){var r=start();assertThrows(ServiceException.class,()->meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",null,null,5L,"served",200,1,true,false)));assertEquals("DISPATCHED",row().get("status"));assertEquals("200",token().get("reserved"));}
    @Test void manualNotDispatchedRequiresNoContradictingRealResponse(){var r=start();meter.settle(r.id(),new MesModelUsageService.Settlement("SUCCESS",null,null,null,"served",200,1,true,false));clock.advance();assertThrows(ServiceException.class,()->meter.reconcile(new ReconcileRequest(r.id(),version(),"CONFIRM_NOT_DISPATCHED",null,null,null,"已有HTTP响应，不能伪称未发出")));assertEquals("SETTLED",row().get("status"));}
    @Test void tenantActorMismatchAndForeignReconciliationDoNotExposeAnotherTenantsLog(){var r=start();TenantContextHolder.setTenantId(2L);assertThrows(ServiceException.class,meter::summary);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(10L).setTenantId(2L).setUserType(2),new MockHttpServletRequest());assertEquals(0L,meter.page(new UsagePageRequest(null,null,null,null,null,1,20)).get("total"));assertThrows(ServiceException.class,()->meter.reconcile(new ReconcileRequest(r.id(),2,"CONFIRM_NOT_DISPATCHED",null,null,null,"其他租户无权核销")));}
}
