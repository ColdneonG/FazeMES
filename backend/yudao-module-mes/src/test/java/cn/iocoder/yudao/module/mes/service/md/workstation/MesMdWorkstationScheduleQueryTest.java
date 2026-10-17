package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.MesMdWorkstationScheduleMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the production calendar join with the real tenant interceptor. */
class MesMdWorkstationScheduleQueryTest {
    private SqlSession session;
    private final LocalDateTime previous = LocalDateTime.of(2026, 10, 16, 0, 0);
    private final LocalDateTime next = LocalDateTime.of(2026, 10, 18, 0, 0);
    @BeforeEach void setUp() throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:resources" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new LongValue(1); }
        }));
        config.addInterceptor(interceptor); config.addMapper(MesMdWorkstationScheduleMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        execute("CREATE TABLE mes_cal_team_member(user_id bigint,team_id bigint,deleted int,tenant_id bigint)");
        execute("CREATE TABLE mes_cal_plan_team(plan_id bigint,team_id bigint,deleted int,tenant_id bigint)");
        execute("CREATE TABLE mes_cal_plan(id bigint,start_date timestamp,end_date timestamp,status int,deleted int,tenant_id bigint)");
        execute("CREATE TABLE mes_cal_team_shift(plan_id bigint,team_id bigint,shift_id bigint,`day` timestamp,deleted int,tenant_id bigint)");
        execute("CREATE TABLE mes_cal_plan_shift(id bigint,plan_id bigint,start_time varchar(5),end_time varchar(5),deleted int,tenant_id bigint)");
    }
    @AfterEach void tearDown() { if (session != null) session.close(); }
    private void execute(String sql) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) { statement.execute(sql); }
    }
    @Test void confirmedCalendarsKeepYesterdayNightShiftAndExcludeDraftDeletedAndOtherTenants() throws Exception {
        execute("INSERT INTO mes_cal_team_member VALUES (5,10,0,1),(5,20,0,2)");
        execute("INSERT INTO mes_cal_plan VALUES (1,'2026-10-16','2026-10-17',1,0,1),(2,'2026-10-16','2026-10-17',0,0,1),(3,'2026-10-16','2026-10-17',1,1,1),(4,'2026-10-16','2026-10-17',1,0,2)");
        execute("INSERT INTO mes_cal_plan_team VALUES (1,10,0,1),(2,10,0,1),(3,10,0,1),(4,20,0,2)");
        execute("INSERT INTO mes_cal_plan_shift VALUES (101,1,'22:00','06:00',0,1),(102,2,'08:00','16:00',0,1),(103,3,'08:00','16:00',0,1),(104,4,'08:00','16:00',0,2)");
        execute("INSERT INTO mes_cal_team_shift VALUES (1,10,101,'2026-10-16',0,1),(1,10,101,'2026-10-15',0,1),(1,10,101,'2026-10-18',0,1),(2,10,102,'2026-10-17',0,1),(3,10,103,'2026-10-17',0,1),(4,20,104,'2026-10-17',0,2)");
        var rows = session.getMapper(MesMdWorkstationScheduleMapper.class).selectPersonalShifts(5L, previous, next);
        assertEquals(1, rows.size()); assertEquals(previous, rows.get(0).getDay());
        assertEquals("22:00", rows.get(0).getStartTime()); assertEquals("06:00", rows.get(0).getEndTime());
        assertTrue(session.getMapper(MesMdWorkstationScheduleMapper.class).selectPersonalShifts(999L, previous, next).isEmpty());
    }
    @Test void currentConfirmedPlanWithoutADayShiftIsReturnedAsRestrictedRestDay() throws Exception {
        execute("INSERT INTO mes_cal_team_member VALUES (5,10,0,1)");
        execute("INSERT INTO mes_cal_plan_team VALUES (1,10,0,1)");
        execute("INSERT INTO mes_cal_plan VALUES (1,'2026-10-17','2026-10-17',1,0,1)");
        var rows = session.getMapper(MesMdWorkstationScheduleMapper.class).selectPersonalShifts(5L, previous, next);
        assertEquals(1, rows.size()); assertNull(rows.get(0).getDay()); assertNull(rows.get(0).getStartTime());
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), rows.get(0).getPlanStart());
    }
}
