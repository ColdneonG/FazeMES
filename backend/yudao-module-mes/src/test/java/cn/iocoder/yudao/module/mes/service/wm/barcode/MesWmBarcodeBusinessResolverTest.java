package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesWmBarcodeBusinessResolverTest {
    JdbcTemplate jdbc; MesWmBarcodeBusinessResolver resolver;
    record Case(int type,String table,String code,String name,Integer status) { }
    static final List<Case> CASES=List.of(
            new Case(102,"mes_wm_warehouse","WH-1","原料仓",null),
            new Case(103,"mes_wm_warehouse_location","LOC-1","库区一",null),
            new Case(104,"mes_wm_warehouse_area","AREA-1","库位一",0),
            new Case(105,"mes_wm_package","PACK-1","PACK-1",4),
            new Case(106,"mes_wm_material_stock","1","物料一",null),
            new Case(107,"mes_wm_batch","BATCH-1","物料一",null),
            new Case(109,"mes_wm_sn","SN-REAL-001","SN-REAL-001",null),
            new Case(300,"mes_pro_card","PC-1","PC-1",2),
            new Case(301,"mes_pro_work_order","WO-1","工单一",2),
            new Case(400,"mes_dv_machinery","MACHINE-1","车床一",3),
            new Case(500,"mes_tm_tool","TOOL-1","工具一",4),
            new Case(600,"mes_md_item","ITEM-1","物料一",0),
            new Case(601,"mes_md_vendor","VENDOR-1","供应商一",0),
            new Case(602,"mes_md_workstation","WS-1","工作站一",0),
            new Case(603,"mes_md_workshop","SHOP-1","车间一",0),
            new Case(604,"system_users","operator1","操作员一",0),
            new Case(605,"mes_md_client","CLIENT-1","客户一",0));
    static Stream<Case> all() { return CASES.stream(); }
    static Stream<Case> common() { return CASES.stream().filter(c->Set.of(104,600,601,602,603,604,605).contains(c.type())); }
    static Stream<Case> lifecycle() { return CASES.stream().filter(c->Set.of(105,300,301,400,500).contains(c.type())); }
    static Stream<Case> noState() { return CASES.stream().filter(c->c.status()==null); }
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:barcode_identity_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/barcode_business.sql")).execute(ds);
        jdbc=spy(new JdbcTemplate(ds)); resolver=new MesWmBarcodeBusinessResolver(jdbc); TenantContextHolder.setTenantId(1L);
        for(var c:CASES) for(int id=1;id<=3;id++) seed(c,id,id==2?2:1,id==3?1:0);
        clearInvocations(jdbc);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); jdbc.execute("DROP ALL OBJECTS"); }
    void seed(Case c,long id,long tenant,int deleted) {
        if(c.type()==106) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,1.000001,1,1,?,?)",id,1,tenant,deleted);
        else if(c.type()==107) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,1,99,?,?)",id,c.code(),tenant,deleted);
        else if(c.type()==109) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,1,'generation-uuid',?,?)",id,c.code(),tenant,deleted);
        else if(c.type()==105||c.type()==300) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,?,?,?)",id,c.code(),c.status(),tenant,deleted);
        else if(c.type()==604) jdbc.update("INSERT INTO system_users VALUES(?,?,?,?,'DO-NOT-READ-PASSWORD',?,?)",id,c.code(),c.name(),c.status(),tenant,deleted);
        else if(c.type()==102||c.type()==103) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,?,1,?,?)",id,c.code(),c.name(),tenant,deleted);
        else if(c.type()==104) jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,?,?,1,?,?)",id,c.code(),c.name(),c.status(),tenant,deleted);
        else jdbc.update("INSERT INTO "+c.table()+" VALUES(?,?,?,?,?,?)",id,c.code(),c.name(),c.status(),tenant,deleted);
    }
    @ParameterizedTest(name="canonical type {0}") @MethodSource("all")
    void allSeventeenTypesResolveActualCanonicalBusinessFields(Case c) {
        for(boolean print:List.of(false,true)) {
            var result=resolver.resolve(c.type(),1L,print);
            assertEquals(c.type(),result.bizType()); assertEquals(1L,result.bizId()); assertEquals(c.code(),result.bizCode()); assertEquals(c.name(),result.bizName());
            assertFalse(result.toString().contains("DO-NOT-READ-PASSWORD"));
        }
    }
    @ParameterizedTest(name="tenant/deleted type {0}") @MethodSource("all")
    void allSeventeenTypesRejectForeignDeletedAndMissingIds(Case c) {
        for(long id:List.of(2L,3L,999L)) for(boolean print:List.of(false,true)) {
            var failure=assertThrows(ServiceException.class,()->resolver.resolve(c.type(),id,print));
            assertEquals(1_040_703_020,failure.getCode()); assertTrue(failure.getMessage().contains("不存在"));
        }
    }
    @ParameterizedTest(name="common state type {0}") @MethodSource("common")
    void disabledMastersMayRegisterButCannotPrintAndInvalidStatusesFail(Case c) {
        jdbc.update("UPDATE "+c.table()+" SET status=1 WHERE id=1");
        assertEquals(c.code(),resolver.resolve(c.type(),1L,false).bizCode());
        assertTrue(assertThrows(ServiceException.class,()->resolver.resolve(c.type(),1L,true)).getMessage().contains("停用"));
        jdbc.update("UPDATE "+c.table()+" SET status=NULL WHERE id=1");
        for(boolean print:List.of(false,true)) assertThrows(ServiceException.class,()->resolver.resolve(c.type(),1L,print));
        jdbc.update("UPDATE "+c.table()+" SET status=99 WHERE id=1");
        for(boolean print:List.of(false,true)) assertThrows(ServiceException.class,()->resolver.resolve(c.type(),1L,print));
    }
    @ParameterizedTest(name="lifecycle type {0}") @MethodSource("lifecycle")
    void lifecycleStatesAreNotMistakenForMasterDisable(Case c) {
        List<Integer> statuses=switch(c.type()) {
            case 105 -> List.of(0,4);
            case 300,301 -> List.of(0,1,2,3);
            case 400 -> List.of(1,2,3);
            default -> List.of(1,2,3,4);
        };
        for(int status:statuses) {
            jdbc.update("UPDATE "+c.table()+" SET status=? WHERE id=1",status);
            assertEquals(c.code(),resolver.resolve(c.type(),1L,true).bizCode()); assertEquals(c.code(),resolver.resolve(c.type(),1L,false).bizCode());
        }
        for(Integer status:Arrays.asList(null,99)) {
            jdbc.update("UPDATE "+c.table()+" SET status=? WHERE id=1",status);
            for(boolean print:List.of(false,true)) assertThrows(ServiceException.class,()->resolver.resolve(c.type(),1L,print));
        }
    }
    @ParameterizedTest(name="no invented status type {0}") @MethodSource("noState")
    void SourcesWithoutStatusCanBePrintedWhileFrozenOrQualityHeld(Case c) {
        assertEquals(c.code(),resolver.resolve(c.type(),1L,true).bizCode());
    }
    @ParameterizedTest @ValueSource(ints={106,107})
    void stockAndBatchRequireCurrentSameTenantUndeletedItemButAllowDisabledHistoricalItem(int type) {
        jdbc.update("UPDATE mes_md_item SET status=1 WHERE id=1");
        assertEquals("物料一",resolver.resolve(type,1L,true).bizName());
        jdbc.update("UPDATE mes_md_item SET tenant_id=2 WHERE id=1");
        assertTrue(assertThrows(ServiceException.class,()->resolver.resolve(type,1L,true)).getMessage().contains("关联的物料"));
        jdbc.update("UPDATE mes_md_item SET tenant_id=1,deleted=1 WHERE id=1");
        assertThrows(ServiceException.class,()->resolver.resolve(type,1L,false));
        jdbc.update("DELETE FROM mes_md_item WHERE id=1"); assertThrows(ServiceException.class,()->resolver.resolve(type,1L,true));
    }
    @ParameterizedTest @ValueSource(ints={302,303,999})
    void unsupportedTypesRejectBeforeQuery(int type) {
        assertEquals(1_040_703_020,assertThrows(ServiceException.class,()->resolver.resolve(type,1L,true)).getCode()); verifyNoInteractions(jdbc);
    }
    @Test void nullAndNonpositiveSelectorsRejectBeforeQuery() {
        assertThrows(ServiceException.class,()->resolver.resolve(null,1L,false));
        assertThrows(ServiceException.class,()->resolver.resolve(600,null,false));
        assertThrows(ServiceException.class,()->resolver.resolve(600,0L,false));
        assertThrows(ServiceException.class,()->resolver.resolve(600,-1L,true)); verifyNoInteractions(jdbc);
    }
    @Test void missingTenantNeverFallsBackToDefaultTenant() {
        TenantContextHolder.clear(); assertThrows(ServiceException.class,()->resolver.resolve(600,1L,false)); verifyNoInteractions(jdbc);
    }
    @Test void stockIdentityIsActualDecimalRecordIdAndTwoPositionsNeverCollapseToItemCode() {
        jdbc.update("INSERT INTO mes_wm_material_stock VALUES(10,1,2,0,0,1,0),(9007199254740993,1,3,0,0,1,0)");
        assertEquals("1",resolver.resolve(106,1L,true).bizCode()); assertEquals("10",resolver.resolve(106,10L,true).bizCode());
        assertEquals("9007199254740993",resolver.resolve(106,9007199254740993L,true).bizCode());
        assertEquals("ITEM-1",resolver.resolve(600,1L,true).bizCode());
    }
    @Test void blankCanonicalCodeFailsInsteadOfIdOrCallerFallback() {
        jdbc.update("UPDATE mes_md_item SET code=NULL WHERE id=1"); assertThrows(ServiceException.class,()->resolver.resolve(600,1L,false));
        jdbc.update("UPDATE mes_md_item SET code='   ' WHERE id=1"); assertThrows(ServiceException.class,()->resolver.resolve(600,1L,true));
    }
    @Test void exactCanonicalCodeAndOptionalDisplayNameArePreservedWithoutNormalization() {
        jdbc.update("UPDATE mes_md_item SET code=?,name=NULL WHERE id=1"," ITEM-%_\"<>& ");
        var result=resolver.resolve(600,1L,true); assertEquals(" ITEM-%_\"<>& ",result.bizCode()); assertNull(result.bizName());
    }
    @Test void userIdentityUsesUsernameAndNicknameRatherThanIdOrSensitiveColumns() {
        var result=resolver.resolve(604,1L,true); assertEquals("operator1",result.bizCode()); assertEquals("操作员一",result.bizName());
        assertFalse(result.toString().contains("DO-NOT-READ-PASSWORD"));
    }
}
