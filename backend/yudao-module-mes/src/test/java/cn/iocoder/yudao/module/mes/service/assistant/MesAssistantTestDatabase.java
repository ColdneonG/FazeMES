package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.UUID;

abstract class MesAssistantTestDatabase {
    @TempDir Path temporary;
    JdbcTemplate jdbc;
    ObjectMapper json;
    MesAssistantSecretStore secrets;
    MesAssistantModelGateway models;
    MesAssistantService service;
    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:assistant_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/assistant_rag.sql")).execute(source);
        jdbc = new JdbcTemplate(source); json = new ObjectMapper();
        secrets = new MesAssistantSecretStore(new MockEnvironment().withProperty("mes.assistant.master-key-file", temporary.resolve("private/master.key").toString()));
        models = new MesAssistantModelGateway(jdbc, json, secrets);
        service = new MesAssistantService(jdbc, json, new MesAssistantDocumentParser(), new MesAssistantRetriever(), models);
        TenantContextHolder.setTenantId(1L);
    }
    @AfterEach void closeDatabase() { TenantContextHolder.clear(); jdbc.execute("DROP ALL OBJECTS"); }
}
