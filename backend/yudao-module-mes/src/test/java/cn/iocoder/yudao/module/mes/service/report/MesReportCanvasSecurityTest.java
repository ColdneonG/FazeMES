package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportConfig;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.MesReportSaveReqVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the isolated H2 setup and inherited security regression cases; no application is started. */
class MesReportCanvasSecurityTest extends MesReportSecurityTest {
    private MesReportConfig canvasConfig() {
        var config = config();
        var block = new MesReportConfig.Block().setId("component_1").setType("metric")
                .setX(40).setY(140).setWidth(320).setHeight(100).setTitle("计划数量");
        block.getBinding().setDimension("code").setMetrics(List.of("quantity"));
        config.setCanvas(new MesReportConfig.Canvas().setBlocks(new ArrayList<>(List.of(block))));
        return config;
    }

    @Test void canvasVersionsRoundTripWithoutChangingPublishedOrLegacySnapshots() {
        var legacy = new MesReportSaveReqVO().setName("旧报表").setConfig(config());
        long legacyId = ((Number) service.save(legacy, 1).get("id")).longValue();
        assertNull(((MesReportConfig) service.get(legacyId, 1, true).get("config")).getCanvas());
        var config = canvasConfig();
        var request = new MesReportSaveReqVO().setName("画布报表").setConfig(config);
        long reportId = ((Number) service.save(request, 1).get("id")).longValue();
        service.publish(reportId, 1, 1);
        config.getCanvas().getBlocks().get(0).setX(88).setTitle("修改后的标题");
        service.save(request.setId(reportId).setExpectedVersion(1), 1);
        var published = (MesReportConfig) service.get(reportId, null, false).get("config");
        var latest = (MesReportConfig) service.get(reportId, null, true).get("config");
        assertEquals(40, published.getCanvas().getBlocks().get(0).getX());
        assertEquals("计划数量", published.getCanvas().getBlocks().get(0).getTitle());
        assertEquals(88, latest.getCanvas().getBlocks().get(0).getX());
        assertEquals(List.of("quantity"), latest.getCanvas().getBlocks().get(0).getBinding().getMetrics());
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class, () -> service.get(reportId, 2, true));
    }

    @Test void bindingsMustUseVisibleFieldsAndNumericMetrics() {
        var config = canvasConfig();
        assertDoesNotThrow(() -> registry.compile(config, 1, 1, 10));
        var binding = config.getCanvas().getBlocks().get(0).getBinding();
        binding.setDimension("tenant_id");
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        binding.setDimension("code").setMetrics(List.of("code"));
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        binding.setMetrics(List.of("quantity"));
        config.getColumns().get(1).setVisible(false);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        config.getColumns().get(1).setVisible(true);
        binding.setMetrics(List.of("quantity", "quantity"));
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        binding.setMetrics(List.of("SUM(quantity);DROP TABLE users"));
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
    }

    @Test void layoutBoundsIdsTypesAndStyleAreValidatedBeforeQueryExecution() {
        var config = canvasConfig();
        var block = config.getCanvas().getBlocks().get(0);
        block.setX(-1);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        block.setX(40).setWidth(800);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        block.setWidth(320).setType("iframe");
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        block.setType("metric");
        block.getStyle().setBackground("url(https://example.invalid/image)");
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        block.getStyle().setBackground("#ffffff").setFontSize(1000);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        block.getStyle().setFontSize(12);
        config.getCanvas().getBlocks().add(block);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
    }

    @Test void unsafeProviderScriptAndUrlPropertiesCannotBeDeserialized() throws Exception {
        var mapper = new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = mapper.writeValueAsString(canvasConfig());
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(json.replace("\"schemaVersion\":1", "\"sourceUrl\":\"https://example.invalid\",\"schemaVersion\":1"), MesReportConfig.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(json.replace("\"dimension\":\"code\"", "\"script\":\"alert(1)\",\"dimension\":\"code\""), MesReportConfig.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(json.replace("\"dataset\":\"workorders\"", "\"sql\":\"SELECT * FROM system_user\",\"dataset\":\"workorders\""), MesReportConfig.class));
    }

    @Test void emptyCanvasIsAllowedButNullAndOversizedComponentsAreRejected() {
        var config = canvasConfig();
        config.getCanvas().setBlocks(new ArrayList<>());
        assertDoesNotThrow(() -> registry.compile(config, 1, 1, 10));
        config.getCanvas().getBlocks().add(null);
        assertThrows(ServiceException.class, () -> registry.compile(config, 1, 1, 10));
        var valid = canvasConfig();
        var blocks = new ArrayList<MesReportConfig.Block>();
        for (int index = 0; index < 31; index++) blocks.add(new MesReportConfig.Block().setId("b_" + index).setType("text").setX(0).setY(0).setWidth(100).setHeight(40));
        valid.getCanvas().setBlocks(blocks);
        assertThrows(ServiceException.class, () -> registry.compile(valid, 1, 1, 10));
    }
}
