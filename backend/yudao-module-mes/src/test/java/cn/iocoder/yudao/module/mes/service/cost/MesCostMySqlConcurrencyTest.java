package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * InnoDB-specific regressions. Never point these tests at an application database.
 * The caller creates an EMPTY broky_cost_it_* schema and supplies the three MES_COST_MYSQL_* variables.
 * Normal H2 runs skip this class because H2 RR locking reads retain its snapshot.
 */
@EnabledIfEnvironmentVariable(named="MES_COST_MYSQL_URL",matches="jdbc:mysql://.+/broky_cost_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesCostMySqlConcurrencyTest {
    private MesCostServiceTest fixture;
    private DataSource dataSource;
    private String schema;
    private static final Set<String> TABLES=Set.of("mes_cost_profile","mes_cost_workorder","mes_cost_effort","mes_cost_line_price","mes_cost_stock_layer","mes_cost_allocation","mes_cost_return_source_balance","mes_cost_return_allocation","mes_cost_layer_basis","mes_pro_work_order_loss","mes_md_item","mes_md_item_type","mes_md_product_bom","mes_pro_work_order","mes_pro_work_order_bom","mes_pro_task","mes_pro_feedback","mes_wm_transaction","mes_wm_product_produce_line","mes_wm_material_stock","mes_wm_warehouse","mes_wm_item_receipt","mes_wm_item_receipt_line","mes_wm_product_sales","mes_wm_product_sales_line");
    private JdbcTemplate checked() {
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        assertTrue(schema.matches("broky_cost_it_[A-Za-z0-9_]+"),"Only an isolated test schema is allowed");
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class),"Refusing to touch a different schema");
        return jdbc;
    }
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("MES_COST_MYSQL_URL");
        schema=URI.create(url.substring(5)).getPath().substring(1);
        dataSource=new DriverManagerDataSource(url,System.getenv("MES_COST_MYSQL_USER"),System.getenv("MES_COST_MYSQL_PASSWORD"));
        JdbcTemplate jdbc=checked();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",schema).isEmpty(),"The test schema must be empty");
        fixture=new MesCostServiceTest();fixture.setupDataSource(dataSource);
    }
    @AfterEach void clear() {
        TenantContextHolder.clear();
        if(dataSource==null)return;
        JdbcTemplate jdbc=checked();
        List<String> tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",String.class,schema);
        assertTrue(TABLES.containsAll(tables),"Unexpected tables: refusing cleanup");
        for(String table:tables)jdbc.execute("DROP TABLE `"+table+"`");
    }
    @Test void oldReadViewDoesNotFabricateInventoryCostLayers(){fixture.staleInventoryReadViewCannotCreateAnExtraUnknownCostLayer();}
    @Test void justSettledSubassemblyUsesCurrentOriginCosts(){fixture.aSettledLayerAndItsOriginAreReadFromTheSameCurrentState();}
}
