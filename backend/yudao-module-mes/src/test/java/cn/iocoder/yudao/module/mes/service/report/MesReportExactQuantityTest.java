package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.mes.controller.admin.report.MesReportController;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportConfig;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportPreviewReqVO;
import cn.iocoder.yudao.module.mes.dal.mysql.report.MesReportRepository;
import cn.iocoder.yudao.module.mes.framework.web.MesDecimalResponseAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real DECIMAL(20,6) queries verify aliases before the browser parses JSON. */
class MesReportExactQuantityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MesReportService service;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:report_exact_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mes_pro_work_order(id BIGINT,code VARCHAR(100),quantity DECIMAL(20,6),"
                + "status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.update("INSERT INTO mes_pro_work_order(id,code,quantity,status,tenant_id) VALUES"
                + "(1,'A',99999999999999.999999,1,1),(2,'B',0.000001,1,1),(3,'OTHER',5,1,2)");
        var repository = new MesReportRepository();
        ReflectionTestUtils.setField(repository, "jdbc", jdbc);
        service = new MesReportService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "registry", new MesReportDatasetRegistry());
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        TenantContextHolder.setTenantId(1L);
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private MesReportConfig detail() {
        var config = new MesReportConfig().setDataset("workorders");
        config.getColumns().add(new MesReportConfig.Column().setField("code").setTitle("编码"));
        config.getColumns().add(new MesReportConfig.Column().setField("quantity").setTitle("任意列标题"));
        config.getColumns().add(new MesReportConfig.Column().setField("id").setTitle("ID"));
        return config;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preview(MesReportConfig config, boolean export) {
        return (List<Map<String, Object>>) service.preview(new MesReportPreviewReqVO().setConfig(config),
                true, export, true).get("rows");
    }

    @Test
    void aliasesKeepMaxAndMicroQuantitiesExactOnTheActualPreviewHttpRoute() throws Exception {
        var security = mock(SecurityFrameworkService.class);
        when(security.hasPermission("mes:report:design")).thenReturn(true);
        when(security.hasAnyPermissions(any(String[].class))).thenReturn(true);
        var controller = new MesReportController();
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "security", security);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MesDecimalResponseAdvice(mapper), new GlobalResponseBodyHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(post("/mes/report/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MesReportPreviewReqVO().setConfig(detail()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].c1").value("99999999999999.999999"))
                .andExpect(jsonPath("$.data.rows[1].c1").value("0.000001"))
                .andExpect(jsonPath("$.data.rows[0].c2").isNumber());
    }

    @Test
    void sumBeyondOneRowsRangeIsExactWhileCountKeepsItsIntegerMeaning() {
        var config = new MesReportConfig().setDataset("workorders");
        config.getColumns().add(new MesReportConfig.Column().setField("status").setTitle("状态"));
        config.getColumns().add(new MesReportConfig.Column().setField("quantity").setTitle("合计").setAggregate("SUM"));
        config.setGroupBy(List.of("status"));
        assertEquals("100000000000000.000000", preview(config, false).get(0).get("c1"));
        config.getColumns().get(1).setAggregate("COUNT");
        Object count = preview(config, false).get(0).get("c1");
        assertInstanceOf(Number.class, count);
        assertEquals(2, ((Number) count).intValue());
    }

    @Test
    void exportRetainsRawDecimalsForExistingExactExcelCellHandling() {
        var rows = preview(detail(), true);
        assertEquals(new BigDecimal("99999999999999.999999"), rows.get(0).get("c1"));
        assertEquals(new BigDecimal("0.000001"), rows.get(1).get("c1"));
    }
}
