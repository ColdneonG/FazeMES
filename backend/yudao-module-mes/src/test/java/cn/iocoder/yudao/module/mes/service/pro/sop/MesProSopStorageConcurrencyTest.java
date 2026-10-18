package cn.iocoder.yudao.module.mes.service.pro.sop;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2 transactions exercise the lock/unique-key contract used by task snapshot creation. */
class MesProSopStorageConcurrencyTest {
    private DriverManagerDataSource database() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:sop_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/controlled_sop_tables.sql")).execute(ds);
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sop_test_task(id bigint, tenant_id bigint, PRIMARY KEY(tenant_id,id))");
            statement.execute("INSERT INTO sop_test_task VALUES(10,1)");
        }
        return ds;
    }

    @Test
    void concurrentTaskBindingConvergesOnOneFrozenSnapshot() throws Exception {
        DriverManagerDataSource ds = database();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try (Statement s = c.createStatement()) {
                        // Same lock ordering as the service: the task lock precedes SOP snapshot access.
                        s.executeQuery("SELECT id FROM sop_test_task WHERE tenant_id=1 AND id=10 FOR UPDATE").close();
                        try (ResultSet rs = s.executeQuery("SELECT id FROM mes_sop_task_snapshot WHERE tenant_id=1 AND task_id=10")) {
                            if (rs.next()) { long id = rs.getLong(1); c.commit(); return id; }
                        }
                        s.executeUpdate("INSERT INTO mes_sop_task_snapshot(task_id,item_id,process_id,document_id,version_id,version_no,title,steps_json,attachments_json,tenant_id) VALUES(10,30,40,1,2,'A','frozen','[]','[]',1)");
                        try (ResultSet rs = s.executeQuery("SELECT id FROM mes_sop_task_snapshot WHERE tenant_id=1 AND task_id=10")) {
                            assertTrue(rs.next()); long id = rs.getLong(1); c.commit(); return id;
                        }
                    }
                }
            }));
            start.countDown();
            assertEquals(futures.get(0).get(8, TimeUnit.SECONDS), futures.get(1).get(8, TimeUnit.SECONDS));
            try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM mes_sop_task_snapshot")) {
                assertTrue(rs.next()); assertEquals(1, rs.getInt(1));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void databaseUniquenessProtectsRetryAndTenantScope() throws Exception {
        DriverManagerDataSource ds = database();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String insert = "INSERT INTO mes_sop_step_record(snapshot_id,step_id,viewed_at,viewed_by,tenant_id) VALUES(3,'tighten',CURRENT_TIMESTAMP,77,";
            s.executeUpdate(insert + "1)");
            assertThrows(SQLException.class, () -> s.executeUpdate(insert + "1)"));
            assertEquals(1, s.executeUpdate(insert + "2)"));
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM mes_sop_step_record")) {
                assertTrue(rs.next()); assertEquals(2, rs.getInt(1));
            }
        }
    }
}
