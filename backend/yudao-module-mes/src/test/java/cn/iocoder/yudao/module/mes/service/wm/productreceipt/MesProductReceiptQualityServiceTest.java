package cn.iocoder.yudao.module.mes.service.wm.productreceipt;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL boundary checks, isolated in-memory H2; no application/production DB. */
class MesProductReceiptQualityServiceTest {
    private JdbcTemplate jdbc;
    private MesProductReceiptQualityService service;
    private TransactionTemplate transaction;

    @BeforeEach
    void fixture() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:receipt_quality_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE mes_wm_product_produce(id BIGINT PRIMARY KEY,work_order_id BIGINT,status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_product_produce_line(id BIGINT PRIMARY KEY,produce_id BIGINT,item_id BIGINT,batch_id BIGINT,quality_status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,type INT,biz_type INT,biz_id BIGINT,biz_line_id BIGINT,work_order_id BIGINT,item_id BIGINT,batch_id BIGINT,quantity DECIMAL(20,6),tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        service = new MesProductReceiptQualityService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        TenantContextHolder.setTenantId(1L);
    }

    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test
    void sameStockBatchWithNinetyPassTenFailCannotReleaseOneHundred() {
        production(1, 1, 1, 4, 7L, "90");
        production(2, 1, 2, 4, 7L, "10");
        assertThrows(ServiceException.class, () -> validate(7L, "100"));
        assertDoesNotThrow(() -> validate(7L, "90"));
    }

    @Test
    void earlierReceiptConsumesQualifiedAllowanceAndCannotBeRepeated() {
        production(1, 1, 1, 4, 7L, "90");
        assertDoesNotThrow(() -> validate(7L, "90"));
        receiptOut(900L, 1, 7L, "90");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
    }

    @Test
    void eachDetailSeesEarlierOutRowsInTheSameTransaction() {
        production(1, 1, 1, 4, 7L, "90");
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(status -> {
            validate(7L, "30"); receiptOut(900L, 1, 7L, "30");
            validate(7L, "60"); receiptOut(901L, 1, 7L, "60");
            validate(7L, "1");
        }));
    }

    @Test
    void pendingQualityOrUnfinishedProductionCannotAuthorizeReceipt() {
        production(1, 1, 0, 4, 7L, "100");
        production(2, 1, 1, 0, 7L, "100");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
    }

    @Test
    void anotherTenantsProductionAndReceiptsDoNotAffectLocalAllowance() {
        production(1, 2, 1, 4, 7L, "100");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
        production(2, 1, 1, 4, 7L, "50");
        receiptOut(900L, 2, 7L, "100");
        assertDoesNotThrow(() -> validate(7L, "50"));
        jdbc.update("UPDATE mes_wm_product_produce_line SET tenant_id=2 WHERE id=20");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
    }

    @Test
    void anotherOrderItemOrBatchCannotAuthorizeReceipt() {
        production(1, 1, 1, 4, 7L, "100");
        assertThrows(ServiceException.class, () -> service.validateAvailable(2L, 100L, 7L, BigDecimal.ONE));
        assertThrows(ServiceException.class, () -> service.validateAvailable(1L, 101L, 7L, BigDecimal.ONE));
        assertThrows(ServiceException.class, () -> validate(8L, "1"));
        jdbc.update("UPDATE mes_wm_product_produce SET work_order_id=2");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
    }

    @Test
    void deletedLedgerLineOrProductionIsNeverEvidence() {
        production(1, 1, 1, 4, 7L, "10");
        jdbc.update("UPDATE mes_wm_transaction SET deleted=TRUE");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
        jdbc.update("UPDATE mes_wm_transaction SET deleted=FALSE");
        jdbc.update("UPDATE mes_wm_product_produce_line SET deleted=TRUE");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
        jdbc.update("UPDATE mes_wm_product_produce_line SET deleted=FALSE");
        jdbc.update("UPDATE mes_wm_product_produce SET deleted=TRUE");
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
    }

    @Test
    void unbatchedProductsUseTheSameQualifiedQuotaWithoutMatchingOtherBatches() {
        production(1, 1, 1, 4, null, "10");
        assertDoesNotThrow(() -> validate(null, "10"));
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
        receiptOut(900L, 1, null, "10");
        assertThrows(ServiceException.class, () -> validate(null, "1"));
    }

    @Test
    void missingProductionEvidenceAndInvalidQuantitiesAreRejectedExplicitly() {
        assertThrows(ServiceException.class, () -> validate(7L, "1"));
        assertThrows(ServiceException.class, () -> validate(7L, "0"));
        assertThrows(ServiceException.class, () -> validate(7L, "-1"));
        assertThrows(ServiceException.class, () -> service.validateAvailable(null, 100L, 7L, BigDecimal.ONE));
    }

    private void validate(Long batch, String quantity) { service.validateAvailable(1L, 100L, batch, new BigDecimal(quantity)); }

    private void production(long id, long tenant, int quality, int state, Long batch, String quantity) {
        jdbc.update("INSERT INTO mes_wm_product_produce VALUES(?,1,?,?,FALSE)", id, state, tenant);
        jdbc.update("INSERT INTO mes_wm_product_produce_line VALUES(?,?,100,?,?,?,FALSE)", id * 10, id, batch, quality, tenant);
        jdbc.update("INSERT INTO mes_wm_transaction VALUES(?,1,123,?,?,1,100,?,?,?,FALSE)",
                id * 10, id, id * 10, batch, new BigDecimal(quantity), tenant);
    }

    private void receiptOut(long id, long tenant, Long batch, String quantity) {
        jdbc.update("INSERT INTO mes_wm_transaction VALUES(?,2,117,1000,1001,1,100,?,?,?,FALSE)",
                id, batch, new BigDecimal(quantity).negate(), tenant);
    }
}
