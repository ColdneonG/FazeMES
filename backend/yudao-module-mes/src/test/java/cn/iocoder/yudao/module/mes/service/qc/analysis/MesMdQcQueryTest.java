package cn.iocoder.yudao.module.mes.service.qc.analysis;

import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdStockWarningMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.analysis.MesQcAnalysisMapper;
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

/** Uses the real mapper SQL and tenant interceptor against isolated in-memory tables. */
class MesMdQcQueryTest {
    private SqlSession session;
    @BeforeEach void setUp() throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:mdqc" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new LongValue(1); }
        }));
        config.addInterceptor(interceptor);
        config.addMapper(MesMdStockWarningMapper.class); config.addMapper(MesQcAnalysisMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        execute("CREATE TABLE mes_md_item(id bigint,code varchar(32),name varchar(64),status int,safe_stock_flag boolean,min_stock decimal(18,4),max_stock decimal(18,4),deleted boolean,tenant_id bigint)");
        execute("CREATE TABLE mes_wm_material_stock(id bigint,item_id bigint,quantity decimal(18,4),deleted boolean,tenant_id bigint)");
        for (String table : new String[]{"mes_qc_iqc","mes_qc_ipqc","mes_qc_oqc"}) {
            execute("CREATE TABLE " + table + "(id bigint,check_result int,status int,inspect_date timestamp,deleted boolean,tenant_id bigint)");
        }
    }
    @AfterEach void tearDown() { if (session != null) session.close(); }
    private void execute(String sql) throws Exception { try (Statement statement = session.getConnection().createStatement()) { statement.execute(sql); } }

    @Test void stockQueryAggregatesBatchesAndKeepsZeroStockWithoutLeakingAnotherTenant() throws Exception {
        execute("INSERT INTO mes_md_item VALUES (1,'A1','料一',0,TRUE,5,10,FALSE,1),(2,'A2','料二',0,TRUE,5,10,FALSE,1),(3,'B1','其他租户',0,TRUE,5,10,FALSE,2),(4,'D1','停用',1,TRUE,5,10,FALSE,1),(5,'N1','无阈值',0,FALSE,5,10,FALSE,1)");
        execute("INSERT INTO mes_wm_material_stock VALUES (1,1,2,FALSE,1),(2,1,1,FALSE,1),(3,1,100,TRUE,1),(4,1,999,FALSE,2)");
        var rows = session.getMapper(MesMdStockWarningMapper.class).selectStockLevels("A");
        assertEquals(2,rows.size());
        assertEquals(0,new java.math.BigDecimal("3").compareTo(rows.get(0).getStockQuantity()));
        assertEquals(0,rows.get(1).getStockQuantity().signum());
        assertEquals(2,session.getMapper(MesMdStockWarningMapper.class).selectStockLevels(null).size());
    }
    @Test void qualityCountsUseCompletionInspectionDateAndExclusiveEndInAllThreeTenantScopedStages() throws Exception {
        execute("INSERT INTO mes_qc_iqc VALUES (1,1,4,'2026-10-01 00:00:00',FALSE,1),(2,2,4,'2026-10-17 23:59:59',FALSE,1),(3,2,4,'2026-10-18 00:00:00',FALSE,1),(4,2,0,'2026-10-10 00:00:00',FALSE,1),(5,2,4,'2026-10-10 00:00:00',TRUE,1),(6,2,4,'2026-10-10 00:00:00',FALSE,2)");
        execute("INSERT INTO mes_qc_ipqc VALUES (1,1,4,'2026-10-02 00:00:00',FALSE,1)");
        var rows=session.getMapper(MesQcAnalysisMapper.class).selectPeriod(LocalDateTime.of(2026,10,1,0,0),LocalDateTime.of(2026,10,18,0,0),4,1);
        assertEquals(3,rows.size());
        var iqc=rows.stream().filter(r->r.getStage().equals("IQC")).findFirst().orElseThrow();
        assertEquals(2,iqc.getTotal()); assertEquals(1,iqc.getFailed());
        assertEquals(1,rows.stream().filter(r->r.getStage().equals("IPQC")).findFirst().orElseThrow().getTotal());
        assertEquals(0,rows.stream().filter(r->r.getStage().equals("OQC")).findFirst().orElseThrow().getTotal());
    }
}
