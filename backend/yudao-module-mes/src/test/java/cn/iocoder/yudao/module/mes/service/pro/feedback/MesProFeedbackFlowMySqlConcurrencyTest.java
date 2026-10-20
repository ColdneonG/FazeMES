package cn.iocoder.yudao.module.mes.service.pro.feedback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real FlowService, InnoDB row locks and REPEATABLE READ current reads. Never touches application schemas. */
@EnabledIfEnvironmentVariable(named = "MES_FLOW_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:59685/mes_flow_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesProFeedbackFlowMySqlConcurrencyTest {

    private static final Set<String> TABLES = Set.of("mes_pro_work_order", "mes_pro_task", "mes_pro_process",
            "mes_pro_route_process", "mes_pro_feedback", "mes_pro_feedback_source", "mes_pro_feedback_ancestor");
    private MesProFeedbackFlowServiceTest fixture;
    private DataSource dataSource;
    private String schema;
    private boolean ownsSchema;

    @BeforeEach
    void setup() {
        String url = System.getenv("MES_FLOW_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(59685, uri.getPort(), "Only the disposable local MySQL instance is permitted");
        schema = uri.getPath().substring(1);
        assertTrue(schema.matches("mes_flow_it_[A-Za-z0-9_]+"));
        dataSource = new DriverManagerDataSource(url, System.getenv("MES_FLOW_MYSQL_USER"),
                System.getenv("MES_FLOW_MYSQL_PASSWORD"));
        JdbcTemplate jdbc = checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema).isEmpty(),
                "The schema must be EMPTY; do not reuse migration evidence or application schemas");
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        ownsSchema = true;
        fixture = new MesProFeedbackFlowServiceTest();
        fixture.setupDataSource(dataSource);
        // Exercise MySQL's actual BIT(1) JDBC representations, not the H2 fixture's INT flags.
        jdbc.execute("ALTER TABLE mes_pro_feedback MODIFY flow_required BIT NOT NULL DEFAULT b'1', MODIFY flow_complete BIT NOT NULL DEFAULT b'0'");
        jdbc.execute("ALTER TABLE mes_pro_feedback_source MODIFY frozen BIT NOT NULL DEFAULT b'0'");
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND engine='InnoDB'", Integer.class, schema));
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) fixture.clear();
        if (!ownsSchema) return;
        JdbcTemplate jdbc = checked();
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?", String.class, schema);
        assertTrue(TABLES.containsAll(tables), "Unexpected tables: refusing cleanup");
        for (String table : tables) jdbc.execute("DROP TABLE `" + table + "`");
    }

    @Test
    void committedAllocationIsVisibleAfterWaitingOnTheOrderLockUnderRepeatableRead() throws Exception {
        fixture.allocationCommittedWhileApprovalWaitsIsReadFromCurrentState();
    }

    @Test
    void pendingInspectionAlreadyReservesTheSourceAndCannotBeAllocatedAgain() {
        fixture.pendingFeedbackReservesCapacityAndSecondApprovalCannotOverallocate();
    }

    @Test
    void downstreamFailureRollsBackSourceFreezeAncestorsAndCompletenessTogether() {
        fixture.downstreamFailureRollsBackFrozenTransfersAndClosure();
    }

    @Test
    void splitMergeAncestryAndSixDecimalQuantitiesSurviveNativeMysql() {
        fixture.mergesAndSplitsPreserveExactDecimalsAndImmutableAncestorClosure();
    }

    private JdbcTemplate checked() {
        assertTrue(schema.matches("mes_flow_it_[A-Za-z0-9_]+"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        return jdbc;
    }
}
