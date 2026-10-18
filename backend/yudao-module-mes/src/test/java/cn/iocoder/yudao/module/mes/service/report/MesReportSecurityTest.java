package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.*;
import cn.iocoder.yudao.module.mes.controller.admin.report.MesReportController;
import cn.iocoder.yudao.module.mes.dal.dataobject.report.MesReportDefinitionDO;
import cn.iocoder.yudao.module.mes.dal.mysql.report.MesReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes generated queries and immutable version storage on independent H2 tables. */
class MesReportSecurityTest {
    MesReportDatasetRegistry registry=new MesReportDatasetRegistry();
    JdbcTemplate jdbc;MesReportRepository repository;MesReportService service;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:report_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/report_designer_tables.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);repository=new MesReportRepository();ReflectionTestUtils.setField(repository,"jdbc",jdbc);
        service=new MesReportService();ReflectionTestUtils.setField(service,"repository",repository);ReflectionTestUtils.setField(service,"registry",registry);ReflectionTestUtils.setField(service,"objectMapper",new ObjectMapper());
        TenantContextHolder.setTenantId(1L);
        jdbc.update("INSERT INTO mes_pro_work_order(id,code,name,quantity,status,tenant_id,deleted) VALUES(1,'A','alpha',2,1,1,0),(2,'B','beta',3,1,1,0),(3,'SECRET','other tenant',1000,1,2,0),(4,'DELETED','deleted',1000,1,1,1)");
    }
    @AfterEach void clear(){TenantContextHolder.clear();}
    MesReportConfig config() {var c=new MesReportConfig().setDataset("workorders");c.getColumns().add(new MesReportConfig.Column().setField("code").setTitle("编码"));c.getColumns().add(new MesReportConfig.Column().setField("quantity").setTitle("数量"));return c;}
    @Test void tenantAndDeletedAreMandatoryEvenWithoutInterceptors() {
        var rows=repository.query(registry.compile(config(),1,1,50));assertEquals(2,rows.size());assertEquals("A",rows.get(0).get("c0"));
        var other=repository.query(registry.compile(config(),2,1,50));assertEquals(1,other.size());assertEquals("SECRET",other.get(0).get("c0"));
    }
    @Test void injectionIsBoundAndIdentifiersAreRejected() {
        var c=config();c.getFilters().add(new MesReportConfig.Filter().setField("code").setValue("A' OR 1=1 --"));
        var q=registry.compile(c,1,1,50);assertFalse(q.sql().contains("OR 1=1"));assertTrue(repository.query(q).isEmpty());
        c.getColumns().get(0).setField("code);DROP TABLE mes_pro_work_order;--");assertThrows(ServiceException.class,()->registry.compile(c,1,1,50));
        var bad=config().setSortField("id DESC;SELECT 1");assertThrows(ServiceException.class,()->registry.compile(bad,1,1,50));
    }
    @Test void aggregatesOperateOnWholeTenantDatasetBeforePaging() {
        var c=config();c.getColumns().get(0).setField("status");c.getColumns().get(1).setAggregate("SUM");c.setGroupBy(List.of("status"));
        var rows=repository.query(registry.compile(c,1,1,1));assertEquals(1,rows.size());assertEquals(0,new java.math.BigDecimal("5").compareTo((java.math.BigDecimal)rows.get(0).get("c1")));
        c.setGroupBy(List.of());assertThrows(ServiceException.class,()->registry.compile(c,1,1,50));
        c.getColumns().get(1).setAggregate("java.lang.Runtime");assertThrows(ServiceException.class,()->registry.compile(c,1,1,50));
    }
    @Test void versionsAreImmutablePublishedReadIsPinnedAndCrossTenantFails() {
        var req=new MesReportSaveReqVO().setName("report").setConfig(config());var created=service.save(req,1);long id=((Number)created.get("id")).longValue();
        service.publish(id,1,1);req.setId(id).setExpectedVersion(1);req.getConfig().getColumns().get(0).setTitle("修改后");service.save(req,1);
        assertEquals(1,service.get(id,null,false).get("version"));assertEquals(2,service.get(id,null,true).get("version"));
        assertThrows(ServiceException.class,()->service.save(req,1));assertThrows(ServiceException.class,()->service.publish(id,1,1));
        assertThrows(ServiceException.class,()->service.get(id,2,false));
        assertEquals("编码",((MesReportConfig)service.get(id,1,true).get("config")).getColumns().get(0).getTitle());
        TenantContextHolder.setTenantId(2L);assertTrue(service.list().isEmpty());assertThrows(ServiceException.class,()->service.get(id,1,true));assertThrows(ServiceException.class,()->service.publish(id,2,1));
    }
    @Test void limitsAndTypedFiltersFailBeforeExecution() {
        var c=config();assertThrows(ServiceException.class,()->registry.compile(c,1,0,10));assertThrows(ServiceException.class,()->registry.compile(c,1,1,5001));
        c.getFilters().add(new MesReportConfig.Filter().setField("quantity").setValue("not-a-number"));assertThrows(ServiceException.class,()->registry.compile(c,1,1,10));
        c.getFilters().clear();c.getColumns().get(0).setVisible(false);c.getColumns().get(1).setVisible(false);assertThrows(ServiceException.class,()->registry.compile(c,1,1,10));
    }
    @Test void readAndPublishPermissionsDoNotGrantUnsavedDesignPreview() {
        var request=new MesReportPreviewReqVO().setConfig(config());
        assertThrows(ServiceException.class,()->service.preview(request,false,false,false));
        assertThrows(ServiceException.class,()->service.preview(request,false,false,true));
        assertEquals(2,((List<?>)service.preview(request,true,false,true).get("rows")).size());
        TenantContextHolder.clear();assertThrows(NullPointerException.class,()->service.list());
    }
    @Test void viewerNamesAndMetadataFollowPublishedSnapshotOnly() {
        var req=new MesReportSaveReqVO().setName("已发布名称A").setConfig(config());
        long id=((Number)service.save(req,1).get("id")).longValue();
        assertTrue(service.list(false).isEmpty());assertEquals("已发布名称A",service.list(true).get(0).getName());
        service.publish(id,1,1);
        var published=(MesReportDefinitionDO)service.get(id,null,false).get("definition");
        req.setId(id).setExpectedVersion(1).setName("未发布草稿B");service.save(req,1);
        // Definition edits must not leak through the viewer's timestamp either.
        jdbc.update("UPDATE mes_report_definition SET update_time='2099-01-01 00:00:00' WHERE id=?",id);
        var viewerList=service.list(false).get(0);
        var viewerGet=(MesReportDefinitionDO)service.get(id,null,false).get("definition");
        assertEquals("已发布名称A",viewerList.getName());assertEquals("已发布名称A",viewerGet.getName());
        assertEquals(1,viewerList.getLatestVersion());assertEquals(1,viewerGet.getLatestVersion());
        assertEquals(published.getUpdateTime(),viewerList.getUpdateTime());assertEquals(published.getUpdateTime(),viewerGet.getUpdateTime());
        assertEquals("未发布草稿B",service.list(true).get(0).getName());
        assertEquals("未发布草稿B",((MesReportDefinitionDO)service.get(id,null,true).get("definition")).getName());
        assertEquals("已发布名称A",((MesReportDefinitionDO)service.get(id,1,true).get("definition")).getName());
        service.publish(id,2,1);
        assertEquals("未发布草稿B",service.list(false).get(0).getName());
        var after=(MesReportDefinitionDO)service.get(id,null,false).get("definition");
        assertEquals("未发布草稿B",after.getName());assertEquals(2,after.getLatestVersion());assertEquals(2,after.getPublishedVersion());
    }
    @Test void mutationRoutesRequireSeparatePermissionsAndCannotUseGet() throws Exception {
        var save=MesReportController.class.getMethod("save",MesReportSaveReqVO.class);
        var publish=MesReportController.class.getMethod("publish",Long.class,Integer.class);
        var export=MesReportController.class.getMethod("export",MesReportPreviewReqVO.class,jakarta.servlet.http.HttpServletResponse.class);
        assertNotNull(save.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertNotNull(publish.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class));
        assertNotNull(export.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertNull(save.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        assertNull(publish.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        assertNull(export.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        assertEquals("@ss.hasPermission('mes:report:design')",save.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        assertEquals("@ss.hasPermission('mes:report:publish')",publish.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        assertEquals("@ss.hasPermission('mes:report:export')",export.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }
    @Test void reportRowsVersionHistoryAndPreviewTimestampUseIsoWireValues() {
        jdbc.update("UPDATE mes_pro_work_order SET request_date='2026-10-18 08:30:00'");
        var c=new MesReportConfig().setDataset("workorders");
        c.getColumns().add(new MesReportConfig.Column().setField("request_date").setTitle("需求日期"));
        var rows=repository.query(registry.compile(c,1,1,50));
        assertEquals("2026-10-18T08:30:00",rows.get(0).get("c0"));
        long id=((Number)service.save(new MesReportSaveReqVO().setName("ISO dates").setConfig(c),1).get("id")).longValue();
        var created=repository.versions(id,1).get(0).get("createTime");
        assertInstanceOf(String.class,created);assertDoesNotThrow(()->java.time.LocalDateTime.parse((String)created));
        var preview=service.preview(new MesReportPreviewReqVO().setId(id),true,false,true);
        assertInstanceOf(String.class,preview.get("generatedAt"));
        assertDoesNotThrow(()->java.time.LocalDateTime.parse((String)preview.get("generatedAt")));
    }
}
