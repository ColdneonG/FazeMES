package cn.iocoder.yudao.module.mes.service.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

final class MesInsightInventoryFixture {
    static final List<String> HEADERS=List.of("mes_wm_item_receipt","mes_wm_transfer","mes_wm_misc_issue","mes_wm_misc_receipt",
            "mes_wm_product_issue","mes_wm_return_issue","mes_wm_product_receipt","mes_wm_product_sales","mes_wm_return_sales",
            "mes_wm_outsource_issue","mes_wm_outsource_receipt","mes_wm_return_vendor");
    static final List<String> CORE=List.of("mes_md_item","mes_md_item_type","mes_md_unit_measure","mes_wm_warehouse","mes_wm_warehouse_location",
            "mes_wm_warehouse_area","mes_wm_material_stock","mes_wm_transaction");
    static void create(JdbcTemplate j,boolean mysql) {
        String engine=mysql?" ENGINE=InnoDB":"";
        j.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),item_type_id BIGINT,unit_measure_id BIGINT,deleted INT DEFAULT 0)"+engine);
        j.execute("CREATE TABLE mes_md_item_type(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),parent_id BIGINT,deleted INT DEFAULT 0)"+engine);
        for(String table:List.of("mes_md_unit_measure","mes_wm_warehouse","mes_wm_warehouse_location","mes_wm_warehouse_area"))
            j.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),name VARCHAR(100),deleted INT DEFAULT 0)"+engine);
        j.execute("CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,batch_id BIGINT,batch_code VARCHAR(100),warehouse_id BIGINT,quantity DECIMAL(20,6),create_time TIMESTAMP NULL,deleted INT DEFAULT 0,frozen INT DEFAULT 0,quality_hold INT DEFAULT 0)"+engine);
        j.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT,material_stock_id BIGINT,item_id BIGINT,batch_id BIGINT,batch_code VARCHAR(100),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,quantity DECIMAL(20,6),transaction_time TIMESTAMP NULL,biz_type INT,biz_id BIGINT,biz_line_id BIGINT,biz_code VARCHAR(100),related_transaction_id BIGINT,deleted INT DEFAULT 0)"+engine);
        for(String table:HEADERS) j.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),status INT,deleted INT DEFAULT 0,private_client_name VARCHAR(100),private_price DECIMAL(20,6))"+engine);
        j.update("INSERT INTO mes_md_item_type VALUES(1,1,'RM','原材料',0,0),(2,1,'PART','零件',1,0),(3,1,'OTHER','其他',0,0),(4,2,'PRIVATE','其他租户',0,0)");
        j.update("INSERT INTO mes_md_unit_measure VALUES(1,1,'PCS','件',0),(2,1,'KG','千克',0)");
        j.update("INSERT INTO mes_md_item VALUES(1,1,'RM-1','原料1',1,1,0),(2,1,'RM-2','原料2',2,2,0),(3,1,'P-3','其他',3,1,0),(4,2,'PRIVATE','其他租户',4,1,0)");
        j.update("INSERT INTO mes_wm_warehouse VALUES(1,1,'NORMAL','原料仓',0),(2,1,'WIP','线边仓',0),(3,1,'QUARANTINE','质量隔离仓',0),(4,2,'PRIVATE','其他租户',0)");
        j.update("INSERT INTO mes_wm_warehouse_location VALUES(1,1,'L1','库区1',0)");
        j.update("INSERT INTO mes_wm_warehouse_area VALUES(1,1,'A1','库位1',0)");
    }
    static void drop(JdbcTemplate j) {
        for(String table:HEADERS) j.execute("DROP TABLE IF EXISTS "+table);
        for(String table:CORE) j.execute("DROP TABLE IF EXISTS "+table);
    }
    static void stock(JdbcTemplate j,long id,long item,long warehouse,String batch,String quantity,String created) {
        j.update("INSERT INTO mes_wm_material_stock(id,tenant_id,item_id,batch_id,batch_code,warehouse_id,quantity,create_time) VALUES(?,1,?,?,?,?,?,?)",
                id,item,id,batch,warehouse,new java.math.BigDecimal(quantity),created);
    }
    static void tx(JdbcTemplate j,long id,long stock,long item,long warehouse,String batch,String quantity,String time,int bizType,long bizId,String code) {
        j.update("INSERT INTO mes_wm_transaction(id,tenant_id,material_stock_id,item_id,batch_id,batch_code,warehouse_id,location_id,area_id,quantity,transaction_time,biz_type,biz_id,biz_line_id,biz_code) VALUES(?,1,?,?,?,?,?,1,1,?,?,?,?,?,?)",
                id,stock,item,stock,batch,warehouse,new java.math.BigDecimal(quantity),time,bizType,bizId,bizId*10,code);
    }
}
