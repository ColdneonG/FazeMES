package cn.iocoder.yudao.module.mes.service.pro.integrity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.MesProWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MesProductionIntegrityServiceTest {
    JdbcTemplate jdbc;
    MesProductionIntegrityService service;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:integrity_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        String[] ddl = {
                "CREATE TABLE mes_pro_integrity_lock(tenant_id BIGINT,scope VARCHAR(32),PRIMARY KEY(tenant_id,scope))",
                "CREATE TABLE mes_pro_route(id BIGINT,status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_route_process(id BIGINT,route_id BIGINT,process_id BIGINT,sort INT,key_flag BOOLEAN,check_flag BOOLEAN,link_type INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_route_product(route_id BIGINT,item_id BIGINT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_route_product_bom(route_id BIGINT,product_id BIGINT,process_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_md_product_bom(item_id BIGINT,bom_item_id BIGINT,quantity DECIMAL(20,6),status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_md_item(id BIGINT,item_type_id BIGINT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_md_item_type(id BIGINT,item_or_product VARCHAR(16),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_work_order(id BIGINT,parent_id BIGINT,product_id BIGINT,quantity DECIMAL(20,6),quantity_produced DECIMAL(20,6),quantity_changed DECIMAL(20,6),status INT,type INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_work_order_bom(work_order_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_task(id BIGINT,work_order_id BIGINT,quantity DECIMAL(20,6),changed_quantity DECIMAL(20,6),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_pro_feedback(id BIGINT,task_id BIGINT,work_order_id BIGINT,route_id BIGINT,process_id BIGINT,feedback_quantity DECIMAL(20,6),qualified_quantity DECIMAL(20,6),status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE mes_wm_material_stock(work_order_id BIGINT,quantity DECIMAL(20,6),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)"
        };
        for (String sql : ddl) jdbc.execute(sql);
        service = new MesProductionIntegrityService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        TenantContextHolder.setTenantId(1L);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    void route() {
        jdbc.update("INSERT INTO mes_pro_route VALUES(1,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_route_process VALUES(1,1,10,10,FALSE,FALSE,3,1,FALSE),(2,1,20,20,TRUE,TRUE,3,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_route_product VALUES(1,100,1,FALSE)");
        jdbc.update("INSERT INTO mes_md_product_bom VALUES(100,201,1,0,1,FALSE),(100,202,4,0,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_route_product_bom VALUES(1,100,10,201,1,1,FALSE),(1,100,20,202,4,1,FALSE)");
    }
    @Test void activationRequiresExactConsumptionAndFinalUniqueOutput() {
        route(); assertDoesNotThrow(() -> service.validateRoute(1L));
        jdbc.update("UPDATE mes_pro_route_product_bom SET quantity=3 WHERE item_id=202");
        assertThrows(ServiceException.class, () -> service.validateRoute(1L));
        jdbc.update("UPDATE mes_pro_route_product_bom SET quantity=4 WHERE item_id=202");
        jdbc.update("UPDATE mes_pro_route_process SET key_flag=TRUE,check_flag=TRUE WHERE process_id=10");
        jdbc.update("UPDATE mes_pro_route_process SET key_flag=FALSE,check_flag=FALSE WHERE process_id=20");
        assertThrows(ServiceException.class, () -> service.validateRoute(1L));
    }
    @Test void routeCannotConsumeAnotherProductOrAnotherTenantsBom() {
        route(); jdbc.update("UPDATE mes_pro_route_product_bom SET item_id=999 WHERE item_id=202");
        assertThrows(ServiceException.class, () -> service.validateRoute(1L));
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class, () -> service.validateRoute(1L));
    }
    @Test void childOrdersMustBelongToParentBomAndCannotExceedDemand() {
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,1,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom VALUES(1,200,100,1,FALSE)");
        jdbc.update("INSERT INTO mes_md_item_type VALUES(1,'PRODUCT',1,FALSE)");
        jdbc.update("INSERT INTO mes_md_item VALUES(200,1,1,FALSE)");
        var req = new MesProWorkOrderSaveReqVO(); req.setParentId(1L); req.setProductId(200L); req.setQuantity(new BigDecimal("100"));
        assertDoesNotThrow(() -> service.validateChildWorkOrder(req, null));
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(2,1,200,60,0,0,0,1,1,FALSE)");
        req.setQuantity(new BigDecimal("41")); assertThrows(ServiceException.class, () -> service.validateChildWorkOrder(req, null));
        req.setQuantity(new BigDecimal("40")); assertDoesNotThrow(() -> service.validateChildWorkOrder(req, null));
        req.setProductId(999L); assertThrows(ServiceException.class, () -> service.validateChildWorkOrder(req, null));
        TenantContextHolder.setTenantId(2L); assertThrows(ServiceException.class, () -> service.validateChildWorkOrder(req, null));
    }
    MesProFeedbackDO feedback(String quantity) {
        return new MesProFeedbackDO().setId(99L).setTaskId(2L).setWorkOrderId(1L).setRouteId(1L).setProcessId(20L).setFeedbackQuantity(new BigDecimal(quantity));
    }
    @Test void predecessorQualifiedReleaseAndPendingInspectionReserveCapacity() {
        route(); jdbc.update("INSERT INTO mes_pro_task VALUES(2,1,100,0,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,1,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,1,1,1,10,60,50,4,1,FALSE),(2,2,1,1,20,20,0,3,1,FALSE)");
        assertDoesNotThrow(() -> service.validateFeedbackSequence(feedback("30")));
        assertThrows(ServiceException.class, () -> service.validateFeedbackSequence(feedback("31")));
        jdbc.update("UPDATE mes_pro_feedback SET qualified_quantity=100 WHERE id=1");
        jdbc.update("UPDATE mes_pro_task SET quantity=20 WHERE id=2");
        assertThrows(ServiceException.class, () -> service.validateFeedbackSequence(feedback("1")));
    }
    @Test void noPredecessorOutputCannotBeBypassedAndTenantDoesNotLeak() {
        route(); jdbc.update("INSERT INTO mes_pro_task VALUES(2,1,100,0,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,1,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,1,1,1,10,100,100,4,2,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateFeedbackSequence(feedback("1")));
    }
    @Test void finishRequiresReleasedProductionAndEmptyWorkInProcess() {
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,99,0,1,1,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderFinish(1L));
        jdbc.update("UPDATE mes_pro_work_order SET quantity_produced=100");
        jdbc.update("INSERT INTO mes_wm_material_stock VALUES(1,100,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderFinish(1L));
        jdbc.update("UPDATE mes_wm_material_stock SET quantity=0");
        assertDoesNotThrow(() -> service.validateWorkOrderFinish(1L));
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(3,2,1,1,20,1,0,3,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderFinish(1L));
    }
    @Test void splitTasksCannotMultiplyOrderOutput() {
        route();
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,1,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_task VALUES(2,1,100,0,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,1,1,1,10,100,100,4,1,FALSE),(2,3,1,1,20,80,80,4,1,FALSE)");
        assertDoesNotThrow(() -> service.validateFeedbackSequence(feedback("20")));
        assertThrows(ServiceException.class, () -> service.validateFeedbackSequence(feedback("21")));
    }
    @Test void usedRouteIsAnImmutableHistoricalVersion() {
        route();
        jdbc.execute("ALTER TABLE mes_pro_task ADD COLUMN route_id BIGINT");
        assertDoesNotThrow(() -> service.validateRouteEditable(1L));
        jdbc.update("INSERT INTO mes_pro_task(id,work_order_id,quantity,changed_quantity,tenant_id,deleted,route_id) VALUES(2,1,100,0,1,FALSE,1)");
        assertThrows(ServiceException.class, () -> service.validateRouteEditable(1L));
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class, () -> service.validateRouteEditable(1L));
    }
    @Test void confirmationRequiresTheRecipeThatTheRouteWillActuallyConsume() {
        route();
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,0,1,1,FALSE)");
        jdbc.update("INSERT INTO mes_pro_work_order_bom VALUES(1,201,100,1,FALSE),(1,202,400,1,FALSE)");
        assertDoesNotThrow(() -> service.validateWorkOrderRecipe(1L));
        jdbc.update("UPDATE mes_pro_work_order_bom SET quantity=399 WHERE item_id=202");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderRecipe(1L));
        jdbc.update("UPDATE mes_pro_work_order_bom SET quantity=400 WHERE item_id=202");
        jdbc.update("DELETE FROM mes_pro_work_order_bom WHERE item_id=201");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderRecipe(1L));
    }
    @Test void cancellationCannotStrandChildOrdersOrStockOrExecutedReports() {
        jdbc.update("INSERT INTO mes_pro_work_order VALUES(1,0,100,100,0,0,1,1,1,FALSE),(2,1,200,100,0,0,0,1,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderCancel(1L));
        jdbc.update("DELETE FROM mes_pro_work_order WHERE id=2");
        jdbc.update("INSERT INTO mes_wm_material_stock VALUES(1,1,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderCancel(1L));
        jdbc.update("DELETE FROM mes_wm_material_stock");
        jdbc.update("INSERT INTO mes_pro_feedback VALUES(1,1,1,1,10,1,1,4,1,FALSE)");
        assertThrows(ServiceException.class, () -> service.validateWorkOrderCancel(1L));
        jdbc.update("DELETE FROM mes_pro_feedback");
        assertDoesNotThrow(() -> service.validateWorkOrderCancel(1L));
    }
    @Test void graphMutexUsesTenantScopedPersistentKey() {
        assertDoesNotThrow(service::lockBomGraph); assertDoesNotThrow(service::lockBomGraph);
        TenantContextHolder.setTenantId(2L); assertDoesNotThrow(service::lockBomGraph);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_pro_integrity_lock", Integer.class));
    }
}
