package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.cost.vo.MesCostVO.Profile;
import cn.iocoder.yudao.module.mes.service.cost.MesCostService;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real append-only price profiles, with no application database or external model calls. */
class MesAgentPurchasePriceTest {
    JdbcTemplate jdbc;
    MesCostService costs;
    MesAgentExecutionService service;
    SecurityFrameworkService security;
    TransactionTemplate tx;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:agent_price_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        String migration=Files.readString(Path.of("../../database/migrations/20261018_broky_cost.sql"));
        migration=migration.substring(0,migration.indexOf("-- Cost permissions")).replace("b'0'","0");
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));}
        jdbc.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(255),remark VARCHAR(100),deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO mes_md_item VALUES(1,1,'BROKY-R16','Broky电机采购模块','物料原备注',0),(2,1,'NEW-001','新物料','',0),(3,2,'PRIVATE','Broky电机采购模块','',0),(4,1,'DELETED','已删除物料','',1)");
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        security=mock(SecurityFrameworkService.class);when(security.hasPermission(anyString())).thenReturn(true);
        costs=new MesCostService();ReflectionTestUtils.setField(costs,"jdbc",jdbc);
        var costProxy=new ProxyFactory(costs);costProxy.setProxyTargetClass(true);costProxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));costs=(MesCostService)costProxy.getProxy();
        var target=new MesAgentExecutionService();ReflectionTestUtils.setField(target,"jdbc",jdbc);ReflectionTestUtils.setField(target,"security",security);ReflectionTestUtils.setField(target,"costs",costs);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(MesAgentExecutionService)proxy.getProxy();
        login(1,10);
        Profile original=new Profile();original.setItemId(1L);original.setPurchasePrice(new BigDecimal("60"));original.setSalePrice(new BigDecimal("120.123456"));original.setLaborCost(new BigDecimal("2.000001"));original.setOverheadCost(new BigDecimal("3.000002"));original.setRemark("原始定价依据");costs.saveProfile(original);
    }
    private static void login(long tenant,long user){TenantContextHolder.setTenantId(tenant);SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(user).setTenantId(tenant).setUserType(2),new MockHttpServletRequest());}
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();jdbc.execute("DROP ALL OBJECTS");}
    private Map<String,Object> preview(String price){return service.preparePurchasePrice("BROKY-R16",null,price,"本次动作审计说明");}
    private static void amount(String expected,BigDecimal value){assertNotNull(value);assertEquals(0,new BigDecimal(expected).compareTo(value));}

    @Test void previewAndExecutionAppendARealPriceVersionAndPreserveOtherFields(){
        jdbc.update("INSERT INTO mes_cost_workorder(tenant_id,workorder_id,plan_snapshot,plan_quantity) VALUES(1,100,'{\"purchasePrice\":\"60.000000\"}',1)");
        jdbc.update("INSERT INTO mes_cost_line_price(tenant_id,kind,line_id,unit_price,remark) VALUES(1,'RECEIPT',10,55,'原始实际入库价')");
        var payload=service.preparePurchasePrice(null,"Broky电机采购模块","64","这段说明不得覆盖原备注");
        assertEquals("60.000000",payload.get("oldPurchasePrice"));assertEquals("64.000000",payload.get("purchasePrice"));assertEquals(1,payload.get("priceVersion"));assertEquals(2,payload.get("newPriceVersion"));
        assertEquals("CNY",payload.get("currency"));assertEquals("EXCLUSIVE",payload.get("taxBasis"));assertEquals(1,costs.profileHistory(1L).size());
        var receipt=service.execute(payload);assertEquals(1L,receipt.get("businessId"));assertEquals("BROKY-R16",receipt.get("businessCode"));assertEquals(2,receipt.get("priceVersion"));assertTrue(receipt.get("message").toString().contains("已修改为 64.000000"));
        Profile saved=costs.profile(1L);amount("64",saved.getPurchasePrice());amount("120.123456",saved.getSalePrice());amount("2.000001",saved.getLaborCost());amount("3.000002",saved.getOverheadCost());assertEquals("原始定价依据",saved.getRemark());
        assertEquals(2,saved.getVersion());assertEquals(2,costs.profileHistory(1L).size());amount("60",costs.profileHistory(1L).get(1).getPurchasePrice());
        assertEquals("{\"purchasePrice\":\"60.000000\"}",jdbc.queryForObject("SELECT plan_snapshot FROM mes_cost_workorder WHERE workorder_id=100",String.class));amount("55",jdbc.queryForObject("SELECT unit_price FROM mes_cost_line_price WHERE line_id=10",BigDecimal.class));
        assertEquals("物料原备注",jdbc.queryForObject("SELECT remark FROM mes_md_item WHERE id=1",String.class));
    }
    @Test void firstPriceCanBeExplicitZeroWithoutInventingOtherCosts(){
        var payload=service.preparePurchasePrice("NEW-001",null,"0","");assertEquals(0,payload.get("priceVersion"));assertEquals(1,payload.get("newPriceVersion"));assertNull(payload.get("oldPurchasePrice"));
        service.execute(payload);Profile saved=costs.profile(2L);amount("0",saved.getPurchasePrice());assertEquals(1,saved.getVersion());assertNull(saved.getSalePrice());assertNull(saved.getLaborCost());assertNull(saved.getOverheadCost());assertNull(saved.getRemark());
    }
    @Test void invalidAndExcessPrecisionAmountsAreRejectedWithoutRounding(){
        for(String value:Arrays.asList(null,""," ","-1","0.0000001","64.1234567","1000000000000","NaN","六十四")){
            assertThrows(ServiceException.class,()->preview(value),String.valueOf(value));
        }
        assertEquals(1,costs.profileHistory(1L).size());amount("60",costs.profile(1L).getPurchasePrice());
        assertEquals("999999999999.999999",preview("999999999999.999999").get("purchasePrice"));
        assertEquals("0.000001",preview("0.000001").get("purchasePrice"));
    }
    @Test void exactIdentityAndTenantIsolationPreventFuzzyOrAmbiguousPriceUpdates(){
        assertThrows(ServiceException.class,()->service.preparePurchasePrice(null,"电机","64",""));
        assertThrows(ServiceException.class,()->service.preparePurchasePrice("PRIVATE",null,"64",""));
        assertThrows(ServiceException.class,()->service.preparePurchasePrice("DELETED",null,"64",""));
        assertThrows(ServiceException.class,()->service.preparePurchasePrice("BROKY-R16","新物料","64",""));
        assertThrows(ServiceException.class,()->service.preparePurchasePrice("BROKY-%",null,"64",""));
        jdbc.update("INSERT INTO mes_md_item VALUES(5,1,'DUPLICATE','Broky电机采购模块','',0)");
        assertTrue(assertThrows(ServiceException.class,()->service.preparePurchasePrice(null,"Broky电机采购模块","64","")).getMessage().contains("编码"));
        var payload=service.preparePurchasePrice("BROKY-R16","Broky电机采购模块","64","");assertEquals(1L,payload.get("itemId"));
        login(2,20);assertThrows(ServiceException.class,()->service.execute(payload));login(1,10);assertEquals(1,costs.profileHistory(1L).size());
    }
    @Test void changedVersionEvenWithSamePurchaseAmountInvalidatesPreview(){
        var payload=preview("64");Profile another=costs.profile(1L);another.setSalePrice(new BigDecimal("121"));costs.saveProfile(another);
        assertTrue(assertThrows(ServiceException.class,()->service.execute(payload)).getMessage().contains("版本已变化"));
        assertEquals(2,costs.profileHistory(1L).size());amount("60",costs.profile(1L).getPurchasePrice());amount("121",costs.profile(1L).getSalePrice());
    }
    @Test void newFirstVersionAndChangedItemDetailsInvalidateOldPreviews(){
        var first=service.preparePurchasePrice("NEW-001",null,"1","");Profile concurrent=new Profile();concurrent.setItemId(2L);concurrent.setPurchasePrice(new BigDecimal("2"));costs.saveProfile(concurrent);
        assertThrows(ServiceException.class,()->service.execute(first));amount("2",costs.profile(2L).getPurchasePrice());
        var payload=preview("64");jdbc.update("UPDATE mes_md_item SET name='改名后物料' WHERE id=1");assertTrue(assertThrows(ServiceException.class,()->service.execute(payload)).getMessage().contains("物料资料已变化"));amount("60",costs.profile(1L).getPurchasePrice());
    }
    @Test void revokingQueryOrUpdatePermissionPreventsBothPreviewAndExecution(){
        var payload=preview("64");when(security.hasPermission("mes:cost:update")).thenReturn(false);
        assertThrows(ServiceException.class,()->preview("64"));assertThrows(ServiceException.class,()->service.execute(payload));
        when(security.hasPermission("mes:cost:update")).thenReturn(true);when(security.hasPermission("mes:cost:query")).thenReturn(false);
        assertThrows(ServiceException.class,()->preview("64"));assertThrows(ServiceException.class,()->service.execute(payload));assertEquals(1,costs.profileHistory(1L).size());
    }
    @Test void priceVersionParticipatesInTheOwningActionTransaction(){
        var payload=preview("64");assertThrows(ServiceException.class,()->tx.execute(status->{service.execute(payload);throw invalid("模拟回执保存失败");}));
        assertEquals(1,costs.profileHistory(1L).size());amount("60",costs.profile(1L).getPurchasePrice());
        service.execute(payload);amount("64",costs.profile(1L).getPurchasePrice());assertEquals(2,costs.profileHistory(1L).size());
    }
}
