package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MesProductionDashboardAvailabilityTest {
    private JdbcTemplate jdbc;
    private MesProductionDashboardService service;
    private final LocalDate today = LocalDate.of(2026, 10, 31);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:dashboard_availability_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE mes_pro_feedback(tenant_id BIGINT,deleted INT,status INT,workstation_id BIGINT,feedback_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE mes_pro_task(tenant_id BIGINT,deleted INT,status INT,workstation_id BIGINT,start_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE mes_md_workstation(id BIGINT,tenant_id BIGINT,deleted INT,workshop_id BIGINT)");
        service = new MesProductionDashboardService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        TenantContextHolder.setTenantId(1L);
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    private Object latest(Long workshop, Long station) {
        return MesReportTimeFormat.isoResponse(service.availability(workshop, station, today)).get("latestActivityDate");
    }

    @Test
    void excludesForeignDeletedUnfinishedCancelledAndFutureRecords() {
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,0,4,10,'2026-10-25 08:00:00'),"
                + "(2,0,4,10,'2026-10-31 08:00:00'),(1,1,4,10,'2026-10-31 08:00:00'),"
                + "(1,0,3,10,'2026-10-31 08:00:00'),(1,0,4,10,'2026-11-01 00:00:00')");
        jdbc.update("INSERT INTO mes_pro_task VALUES(1,0,0,10,'2026-10-26 08:00:00'),"
                + "(1,0,5,10,'2026-10-31 08:00:00'),(1,1,0,10,'2026-10-31 08:00:00'),"
                + "(2,0,0,10,'2026-10-31 08:00:00'),(1,0,0,10,'2026-11-01 00:00:00')");
        assertEquals("2026-10-26", latest(null, null));
        assertNull(latest(null, 99L));
    }

    @Test
    void intersectsWorkshopAndStationAndDoesNotLeakForeignStationMembership() {
        jdbc.update("INSERT INTO mes_md_workstation VALUES(10,1,0,100),(20,1,0,200),(30,2,0,100),(40,1,1,100)");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,0,4,10,'2026-10-25 08:00:00'),"
                + "(1,0,4,20,'2026-10-30 08:00:00'),(1,0,4,30,'2026-10-31 08:00:00'),"
                + "(1,0,4,40,'2026-10-31 08:00:00')");
        assertEquals("2026-10-25", latest(100L, null));
        assertEquals("2026-10-30", latest(200L, 20L));
        assertNull(latest(100L, 20L));
    }
}
