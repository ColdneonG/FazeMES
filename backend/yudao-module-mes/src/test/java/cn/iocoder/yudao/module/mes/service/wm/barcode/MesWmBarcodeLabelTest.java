package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.MesWmBarcodeSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.config.MesWmBarcodeConfigSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.pro.card.vo.MesProCardSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.service.pro.card.*;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import({MesWmBarcodeServiceImpl.class,MesWmBarcodeConfigServiceImpl.class,MesProCardServiceImpl.class,MesWmBarcodeBusinessResolver.class,MesWmBarcodeMutex.class,MesWmBarcodeRecords.class,MesWmBarcodeLabelTest.Config.class})
// This fixture owns a minimal item schema. Keep it out of the shared BaseDbUnitTest
// database so its CREATE TABLE IF NOT EXISTS cannot narrow another class's metadata.
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:barcode_label;MODE=MYSQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=value,day;")
class MesWmBarcodeLabelTest extends BaseDbUnitTest {
    static class Config { @Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource ds){return new JdbcTemplate(ds);} }
    @Resource MesWmBarcodeServiceImpl service;
    @Resource MesWmBarcodeConfigServiceImpl configs;
    @Resource MesProCardServiceImpl cards;
    @Resource JdbcTemplate jdbc;
    @MockitoBean MesProCardProcessService cardProcesses;
    @MockitoBean MesProWorkOrderService workOrders;
    @MockitoBean MesMdItemService items;
    @BeforeEach void setup(){
        TenantContextHolder.setTenantId(0L);
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_md_item(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.update("DELETE FROM mes_md_item");
        jdbc.update("INSERT INTO mes_md_item(id,code,name,status,tenant_id) VALUES(32,'ITEM-032','轴承',0,0)");
        when(workOrders.validateWorkOrderConfirmed(7L)).thenReturn(new MesProWorkOrderDO().setId(7L).setProductId(32L));
    }
    @AfterEach void clear(){TenantContextHolder.clear();}
    long config(int type){return configs.createBarcodeConfig(new MesWmBarcodeConfigSaveReqVO().setBizType(type).setFormat(1).setContentFormat("{BUSINESSCODE}").setAutoGenerateFlag(true).setDefaultTemplate("LABEL_50X30").setStatus(0));}
    MesWmBarcodeSaveReqVO request(){return new MesWmBarcodeSaveReqVO().setBizType(600).setBizId(32L).setBizCode("FORGED").setBizName("伪造名称").setStatus(0);}
    @Test void createUsesRealCanonicalCodeAndName(){config(600);long id=service.createBarcode(request());var saved=service.getBarcode(id);assertEquals("ITEM-032",saved.getBizCode());assertEquals("轴承",saved.getBizName());assertEquals("ITEM-032",saved.getContent());}
    @Test void unsafeTemplateCannotBeSaved(){assertThrows(ServiceException.class,()->configs.createBarcodeConfig(new MesWmBarcodeConfigSaveReqVO().setBizType(600).setFormat(1).setContentFormat("{BUSINESSCODE}").setAutoGenerateFlag(true).setDefaultTemplate("<script>alert(1)</script>").setStatus(0)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode_config",Integer.class));}
    @Test void actualCardAndBarcodeCollisionRollBackTogether(){long config=config(300);jdbc.update("INSERT INTO mes_wm_barcode(config_id,format,biz_type,content,biz_id,biz_code,status,tenant_id) VALUES(?,1,600,'PC-001',32,'ITEM-032',0,0)",config);assertThrows(ServiceException.class,()->cards.createCard(new MesProCardSaveReqVO().setCode("PC-001").setWorkOrderId(7L).setItemId(32L).setTransferedQuantity(BigDecimal.ONE)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_card",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode",Integer.class));}

    @Test void missingRegistryContextIsReadOnlyAndNotPrintable(){config(600);int mutexRows=count("mes_wm_barcode_mutex");var out=service.getLabelContext(null,600,32L);assertFalse(out.isRegistered());assertNull(out.getBarcodeId());assertNull(out.getContent());assertEquals("ITEM-032",out.getCandidateContent());assertEquals("ITEM-032",out.getBizCode());assertEquals("LABEL_50X30",out.getDefaultTemplate());assertEquals(0,count("mes_wm_barcode"));assertEquals(1,count("mes_wm_barcode_config"));assertEquals(mutexRows,count("mes_wm_barcode_mutex"));}
    @Test void registeredCustomFormatSnapshotSurvivesConfigAndBusinessRename(){long cfg=config(600);jdbc.update("UPDATE mes_wm_barcode_config SET format=3 WHERE id=?",cfg);long id=service.createBarcode(request().setContent("CUSTOM-032"));jdbc.update("UPDATE mes_wm_barcode_config SET format=1,default_template='LABEL_70X40' WHERE id=?",cfg);jdbc.update("UPDATE mes_md_item SET code='RENAMED-32',name='新名称' WHERE id=32");var out=service.getLabelContext(id,null,null);assertTrue(out.isRegistered());assertEquals(3,out.getFormat());assertEquals("CUSTOM-032",out.getContent());assertEquals("RENAMED-32",out.getBizCode());assertEquals("新名称",out.getBizName());assertEquals("LABEL_70X40",out.getDefaultTemplate());assertNull(out.getCandidateContent());assertEquals("ITEM-032",service.getBarcode(id).getBizCode());}
    @Test void legacyBlankTemplateUsesSafeDefaultWithoutRewrite(){long cfg=config(600);jdbc.update("UPDATE mes_wm_barcode_config SET default_template=NULL WHERE id=?",cfg);assertEquals("LABEL_50X30",service.getLabelContext(null,600,32L).getDefaultTemplate());assertNull(jdbc.queryForObject("SELECT default_template FROM mes_wm_barcode_config WHERE id=?",String.class,cfg));}
    @Test void invalidLegacyTemplateRequiresExplicitRepair(){long cfg=config(600);jdbc.update("UPDATE mes_wm_barcode_config SET default_template='<html>old</html>' WHERE id=?",cfg);assertThrows(ServiceException.class,()->service.getLabelContext(null,600,32L));assertEquals("<html>old</html>",configs.getBarcodeConfig(cfg).getDefaultTemplate());assertEquals(0,count("mes_wm_barcode"));}
    @Test void disabledRegistryCannotPrint(){config(600);long id=service.createBarcode(request().setStatus(1));assertThrows(ServiceException.class,()->service.getLabelContext(id,null,null));}
    @Test void aQrControlCharacterCannotBeRegistered(){config(600);assertThrows(ServiceException.class,()->service.createBarcode(request().setContent("ITEM\nX")));assertEquals(0,count("mes_wm_barcode"));}
    @Test void validQrWithSurroundingUnicodeSpaceAndBomPreservesItsExactContent(){config(600);String exact="\u00A0ITEM\uFEFF ";long id=service.createBarcode(request().setContent(exact));assertEquals(exact,service.getBarcode(id).getContent());assertEquals(exact,service.getLabelContext(id,null,null).getContent());}
    @Test void historicalQrWithDelIsRejectedWithoutRewritingItsSavedBytes(){config(600);long id=service.createBarcode(request());String corrupt="ITEM"+(char)127+"X";jdbc.update("UPDATE mes_wm_barcode SET content=? WHERE id=?",corrupt,id);var before=snapshot();assertThrows(ServiceException.class,()->service.getLabelContext(id,null,null));assertEquals(corrupt,service.getBarcode(id).getContent());assertEquals(before,snapshot());}
    @Test void sourceCardAndItsQrRollBackWhenCanonicalCodeContainsAControlCharacter(){config(300);assertThrows(ServiceException.class,()->cards.createCard(card().setCode("PC-\t001")));assertEquals(0,count("mes_pro_card"));assertEquals(0,count("mes_wm_barcode"));}
    @Test void disabledConfigCannotPrintOrCreate(){long cfg=config(600);jdbc.update("UPDATE mes_wm_barcode_config SET status=1 WHERE id=?",cfg);assertThrows(ServiceException.class,()->service.getLabelContext(null,600,32L));assertThrows(ServiceException.class,()->service.createBarcode(request()));assertEquals(0,count("mes_wm_barcode"));}
    @Test void disabledNewItemCanRegisterCanonicallyButCannotPrint(){config(600);jdbc.update("UPDATE mes_md_item SET status=1 WHERE id=32");service.autoGenerateBarcode(600,32L,"FORGED","伪造名称");var row=service.getBarcodeByBizTypeAndBizId(600,32L);assertNotNull(row);assertEquals("ITEM-032",row.getContent());assertEquals("轴承",row.getBizName());assertThrows(ServiceException.class,()->service.getLabelContext(null,600,32L));}
    @Test void duplicateLegacyBusinessIdentityIsNotArbitrarilySelected(){long cfg=config(600);service.createBarcode(request());jdbc.update("INSERT INTO mes_wm_barcode(config_id,format,biz_type,content,biz_id,status,tenant_id) VALUES(?,1,600,'OTHER',32,0,0)",cfg);assertThrows(ServiceException.class,()->service.getLabelContext(null,600,32L));assertEquals(2,count("mes_wm_barcode"));}
    @Test void legacyCrossBusinessSameContentCannotPrint(){long cfg=config(600);long id=service.createBarcode(request());jdbc.update("INSERT INTO mes_wm_barcode(config_id,format,biz_type,content,biz_id,status,tenant_id) VALUES(?,1,600,'ITEM-032',33,0,0)",cfg);assertThrows(ServiceException.class,()->service.getLabelContext(id,null,null));assertEquals(2,count("mes_wm_barcode"));}
    @Test void duplicateLegacyConfigsRequireRepair(){config(600);jdbc.update("INSERT INTO mes_wm_barcode_config(format,biz_type,content_format,auto_generate_flag,default_template,status,tenant_id) VALUES(1,600,'{BUSINESSCODE}',TRUE,'LABEL_50X30',0,0)");assertThrows(ServiceException.class,()->service.getLabelContext(null,600,32L));assertThrows(ServiceException.class,()->service.createBarcode(request()));assertEquals(0,count("mes_wm_barcode"));}
    @Test void sourceCardRollsBackWhenConfiguredFormatIsInvalid(){long cfg=config(300);jdbc.update("UPDATE mes_wm_barcode_config SET format=99 WHERE id=?",cfg);assertThrows(ServiceException.class,()->cards.createCard(card()));assertEquals(0,count("mes_pro_card"));assertEquals(0,count("mes_wm_barcode"));assertEquals(99,configs.getBarcodeConfig(cfg).getFormat());}
    @Test void actualSourceCardAndValidQrRegisterTogether(){config(300);long id=cards.createCard(card());assertEquals(1,count("mes_pro_card"));assertEquals(1,count("mes_wm_barcode"));var out=service.getLabelContext(null,300,id);assertTrue(out.isRegistered());assertEquals("PC-001",out.getContent());assertEquals("PC-001",out.getBizCode());assertEquals(id,out.getBizId());}
    @Test void actualLabelContextSerializesTheContractAndDoesNotChangeAnyRegistryRow() throws Exception {
        config(600);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var before=snapshot();
        var missing=mapper.readTree(mapper.writeValueAsString(service.getLabelContext(null,600,32L)));
        assertEquals(Set.of("barcodeId","registered","configId","bizType","bizId","bizCode","bizName","format","content","candidateContent","defaultTemplate"),keys(missing));
        assertFalse(missing.get("registered").asBoolean());assertTrue(missing.get("barcodeId").isNull());assertTrue(missing.get("content").isNull());
        assertEquals("ITEM-032",missing.get("candidateContent").asText());assertEquals("轴承",missing.get("bizName").asText());
        assertEquals(before,snapshot(),"A missing print context must not register a barcode or acquire/create a mutex row");
        long id=service.createBarcode(request().setContent("CUSTOM-QR"));before=snapshot();
        var existing=mapper.readTree(mapper.writeValueAsString(service.getLabelContext(id,null,null)));
        assertEquals(keys(missing),keys(existing));assertTrue(existing.get("registered").asBoolean());assertEquals(id,existing.get("barcodeId").asLong());
        assertTrue(existing.get("candidateContent").isNull());assertEquals("CUSTOM-QR",existing.get("content").asText());
        assertEquals("LABEL_50X30",existing.get("defaultTemplate").asText());assertEquals(before,snapshot(),"Reading registered print context must preserve every saved row");
    }
    @Test void sourceCreationIsSpringProxiedAndCommitsWithoutAnyTestOuterTransaction(){
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(cards));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        config(300);long id=cards.createCard(card());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(id,jdbc.queryForObject("SELECT biz_id FROM mes_wm_barcode",Long.class));
        assertEquals("PC-001",jdbc.queryForObject("SELECT code FROM mes_pro_card WHERE id=?",String.class,id));
    }
    @Test void updateAlsoUsesCanonicalIdentity(){config(600);long id=service.createBarcode(request());service.updateBarcode(request().setId(id).setBizCode("CHANGED-FORGERY").setContent("CUSTOM"));assertEquals("ITEM-032",service.getBarcode(id).getBizCode());assertEquals("CUSTOM",service.getLabelContext(id,null,null).getContent());}
    @Test void referencedConfigCannotBeDeletedOrRebound(){long cfg=config(600);service.createBarcode(request());assertThrows(ServiceException.class,()->configs.deleteBarcodeConfig(cfg));assertThrows(ServiceException.class,()->configs.updateBarcodeConfig(new MesWmBarcodeConfigSaveReqVO().setId(cfg).setBizType(300).setFormat(1).setContentFormat("{BUSINESSCODE}").setAutoGenerateFlag(true).setStatus(0)));assertEquals(600,configs.getBarcodeConfig(cfg).getBizType());}
    @Test void logicalDeletePermitsNewUniqueRegistration(){config(600);long old=service.createBarcode(request());service.deleteBarcode(old);long fresh=service.createBarcode(request());assertNotEquals(old,fresh);assertEquals(2,count("mes_wm_barcode"));assertEquals(fresh,service.getLabelContext(null,600,32L).getBarcodeId());assertNull(service.getBarcode(old));}
    @Test void contextRejectsInvalidSelectorsAndUsesQueryPermission() throws Exception {assertThrows(ServiceException.class,()->service.getLabelContext(null,null,null));assertThrows(ServiceException.class,()->service.getLabelContext(1L,600,32L));assertThrows(ServiceException.class,()->service.getLabelContext(null,600,null));assertThrows(ServiceException.class,()->service.getLabelContext(null,302,1L));var method=cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.MesWmBarcodeController.class.getMethod("getLabelContext",Long.class,Integer.class,Long.class);assertEquals("@ss.hasPermission('mes:wm-barcode:query')",method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());}
    @Test void newBlankTemplateIsPersistedAsSafePreset(){long cfg=configs.createBarcodeConfig(new MesWmBarcodeConfigSaveReqVO().setBizType(600).setFormat(1).setContentFormat("{BUSINESSCODE}").setAutoGenerateFlag(false).setDefaultTemplate(" ").setStatus(0));assertEquals("LABEL_50X30",configs.getBarcodeConfig(cfg).getDefaultTemplate());}
    @Test void everyPreviouslyMissingSourceCreateNowDeclaresRollbackTransaction() throws Exception {String root="cn.iocoder.yudao.module.mes.service.";String[][] methods={{"wm.warehouse.MesWmWarehouseServiceImpl","createWarehouse"},{"wm.warehouse.MesWmWarehouseLocationServiceImpl","createWarehouseLocation"},{"wm.warehouse.MesWmWarehouseAreaServiceImpl","createWarehouseArea"},{"tm.tool.MesTmToolServiceImpl","createTool"},{"dv.machinery.MesDvMachineryServiceImpl","createMachinery"},{"wm.packages.MesWmPackageServiceImpl","createPackage"},{"md.client.MesMdClientServiceImpl","createClient"},{"md.workstation.MesMdWorkstationServiceImpl","createWorkstation"},{"md.workstation.MesMdWorkshopServiceImpl","createWorkshop"},{"md.vendor.MesMdVendorServiceImpl","createVendor"},{"md.item.MesMdItemServiceImpl","createItem"},{"pro.card.MesProCardServiceImpl","createCard"}};for(var entry:methods){var method=java.util.Arrays.stream(Class.forName(root+entry[0]).getDeclaredMethods()).filter(m->m.getName().equals(entry[1])).findFirst().orElseThrow();var tx=method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);assertNotNull(tx,entry[0]);assertTrue(java.util.Arrays.asList(tx.rollbackFor()).contains(Exception.class),entry[0]);}}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private Map<String,List<Map<String,Object>>> snapshot(){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("mes_wm_barcode","mes_wm_barcode_config","mes_wm_barcode_mutex"))result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+(table.endsWith("mutex")?"tenant_id":"id")));return result;}
    private Set<String> keys(com.fasterxml.jackson.databind.JsonNode node){var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);return fields;}
    private MesProCardSaveReqVO card(){return new MesProCardSaveReqVO().setCode("PC-001").setWorkOrderId(7L).setItemId(32L).setTransferedQuantity(BigDecimal.ONE);}
}
