package cn.iocoder.yudao.module.mes.service.wm.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the actual migration's evidence INSERTs and stock UPDATE against H2 MySQL mode.
 * MySQL procedure/DDL/advisory-lock syntax is not represented as tested by this fixture.
 * No service mock can accidentally replace the source-line/ledger/stock join under test.
 */
class MesWmQualityHistoryMigrationTest {

    private static final LocalDateTime RECEIPT = LocalDateTime.of(2026, 10, 1, 8, 0);
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private String migration;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:quality_history_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve(
                "database/migrations/20261019_quality_quarantine.sql"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "The checked-in migration must be available; never substitute a copied SQL fixture");
        migration = Files.readString(directory.resolve("database/migrations/20261019_quality_quarantine.sql"),
                StandardCharsets.UTF_8);
        jdbc.execute("CREATE TABLE mes_wm_warehouse(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(128),"
                + "remark VARCHAR(500),deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_return_sales(id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_return_issue(id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_return_sales_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,return_id BIGINT,"
                + "item_id BIGINT,batch_id BIGINT,quality_status INT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_return_issue_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,issue_id BIGINT,"
                + "item_id BIGINT,batch_id BIGINT,quality_status INT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_item_receipt(id BIGINT PRIMARY KEY,tenant_id BIGINT,iqc_id BIGINT,vendor_id BIGINT,"
                + "status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_item_receipt_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,receipt_id BIGINT,"
                + "item_id BIGINT,batch_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_qc_iqc(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,vendor_id BIGINT,"
                + "status INT,check_result INT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_outsource_receipt(id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_outsource_receipt_line(id BIGINT PRIMARY KEY,tenant_id BIGINT,receipt_id BIGINT,"
                + "item_id BIGINT,batch_id BIGINT,quality_status INT,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,tenant_id BIGINT,item_id BIGINT,"
                + "batch_id BIGINT,warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,quantity DECIMAL(20,6),"
                + "deleted BOOLEAN DEFAULT FALSE,frozen BOOLEAN DEFAULT FALSE,quality_hold BOOLEAN DEFAULT FALSE,"
                + "quality_hold_reason VARCHAR(500))");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT,type INT,biz_type INT,"
                + "biz_id BIGINT,biz_line_id BIGINT,material_stock_id BIGINT,related_transaction_id BIGINT,"
                + "item_id BIGINT,batch_id BIGINT,warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,"
                + "quantity DECIMAL(20,6),transaction_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE mes_wm_stock_quality_hold(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "material_stock_id BIGINT,source_transaction_id BIGINT NOT NULL,source_biz_type INT,source_biz_id BIGINT,"
                + "source_biz_line_id BIGINT,arrival_transaction_id BIGINT NOT NULL,observed_quantity DECIMAL(20,6),"
                + "reason VARCHAR(500),status VARCHAR(40),UNIQUE(tenant_id,source_transaction_id,arrival_transaction_id))");
        stock(1, 1, 10, 7, 1, "12.500000");
        source(100, 1, 119, 1, 2);
    }

    @Test
    void holdsOnlyTheProvenMixedStockWithoutRewritingQuantityOrLedgers() {
        stock(2, 1, 10, 7, 2, "24"); // Same item/batch, another physical stock is still good.
        stock(3, 2, 10, 7, 1, "30"); // Another tenant is unrelated even at the same dimensions.
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertHeld(3, false);
        assertEquals(0, quantity(1).compareTo(new BigDecimal("12.500000")));
        assertEquals(1, count("mes_wm_transaction"));
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM mes_wm_transaction WHERE id=100", BigDecimal.class)
                .compareTo(new BigDecimal("2")));
        assertEquals(0, jdbc.queryForObject("SELECT observed_quantity FROM mes_wm_stock_quality_hold", BigDecimal.class)
                .compareTo(new BigDecimal("12.500000")), "Observation is the mixed total, not a guessed defective balance");
    }

    @Test
    void retainsDeletedFailLineAndIgnoresMutableHeaderStatus() {
        jdbc.update("UPDATE mes_wm_return_sales_line SET deleted=TRUE WHERE id=1100");
        jdbc.update("UPDATE mes_wm_return_sales SET deleted=TRUE,status=0 WHERE id=900");
        apply();
        assertHeld(1, true);
        assertEquals("PENDING_REVIEW", jdbc.queryForObject("SELECT status FROM mes_wm_stock_quality_hold", String.class));
    }

    @Test
    void supportsProductionReturnInAndDoesNotHoldItsPairedWipOut() {
        jdbc.update("DELETE FROM mes_wm_transaction");
        jdbc.update("DELETE FROM mes_wm_return_sales_line");
        source(101, 1, 116, 1, 2);
        stock(4, 1, 10, 7, 4, "9");
        ledger(90, 1, 2, 116, 900, 1101, 4, null, 10, 7, 4, "-2", RECEIPT.minusSeconds(1));
        jdbc.update("UPDATE mes_wm_transaction SET related_transaction_id=90 WHERE id=101");
        apply();
        assertHeld(1, true);
        assertHeld(4, false);
        assertEquals(1, count("mes_wm_stock_quality_hold"));
        assertEquals(116, jdbc.queryForObject("SELECT source_biz_type FROM mes_wm_stock_quality_hold", Integer.class));
    }

    @Test
    void matchingLineIdCannotCrossTenants() {
        jdbc.update("UPDATE mes_wm_return_sales_line SET tenant_id=2 WHERE id=1100");
        apply();
        assertHeld(1, false);
        assertUnresolved(100);
    }

    @Test
    void changedParentOrItemOrBatchCannotIdentifyStock() {
        for (String change : new String[]{"return_id=999", "item_id=99", "batch_id=999"}) {
            jdbc.update("UPDATE mes_wm_return_sales_line SET return_id=900,item_id=10,batch_id=7");
            jdbc.update("UPDATE mes_wm_return_sales_line SET " + change);
            apply();
            assertHeld(1, false);
            assertUnresolved(100);
            jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        }
    }

    @Test
    void stockIdAloneCannotOverrideTenantLocationOrDeletedState() {
        for (String change : new String[]{"tenant_id=2", "item_id=99", "batch_id=999", "warehouse_id=2",
                "location_id=2", "area_id=2", "deleted=TRUE"}) {
            jdbc.update("UPDATE mes_wm_material_stock SET tenant_id=1,item_id=10,batch_id=7,warehouse_id=1,"
                    + "location_id=1,area_id=1,deleted=FALSE");
            jdbc.update("UPDATE mes_wm_material_stock SET " + change);
            apply();
            assertHeld(1, false);
            assertUnresolved(100);
            jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        }
    }

    @Test
    void missingLineIsReportedWithoutFreezingOtherBatchStock() {
        jdbc.update("DELETE FROM mes_wm_return_sales_line");
        stock(2, 1, 10, 7, 2, "8");
        apply();
        assertUnresolved(100);
        assertHeld(1, false);
        assertHeld(2, false);
    }

    @Test
    void passingReturnAndDeletedOrNonpositiveLedgerAreExcluded() {
        jdbc.update("UPDATE mes_wm_return_sales_line SET quality_status=1");
        apply();
        assertEquals(0, count("mes_wm_stock_quality_hold"));
        jdbc.update("UPDATE mes_wm_return_sales_line SET quality_status=2");
        jdbc.update("UPDATE mes_wm_transaction SET quantity=0");
        apply();
        jdbc.update("UPDATE mes_wm_transaction SET quantity=2,deleted=TRUE");
        apply();
        assertEquals(0, count("mes_wm_stock_quality_hold"));
        assertHeld(1, false);
    }

    @Test
    void ordinaryNewQuarantineReceiptsAreNotHistoricalMixing() {
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(1,1,'QUALITY_QUARANTINE_WAREHOUSE',"
                + "'SYSTEM_QUALITY_QUARANTINE',FALSE)");
        apply();
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void propagatesOnlyAlongActualPairedTransfersAcrossMultipleHops() {
        stock(2, 1, 10, 7, 2, "7");
        stock(3, 1, 10, 7, 3, "6");
        stock(4, 1, 10, 7, 4, "5");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        transfer(202, 203, 2, 3, 2, 3, RECEIPT.plusHours(2));
        ledger(204, 1, 4, 112, 9204, 10204, 4, null, 10, 7, 4, "2", RECEIPT.plusHours(3));
        apply();
        assertHeld(1, true);
        assertHeld(2, true);
        assertHeld(3, true);
        assertHeld(4, false);
        assertEquals(3, count("mes_wm_stock_quality_hold"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_stock_quality_hold WHERE source_transaction_id=100",
                Integer.class));
    }

    @Test
    void zeroBalanceStillRetainsHoldAndFindsDownstreamStock() {
        jdbc.update("UPDATE mes_wm_material_stock SET quantity=0 WHERE id=1");
        stock(2, 1, 10, 7, 2, "2");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        apply();
        assertHeld(1, true);
        assertHeld(2, true);
        assertEquals(0, quantity(1).signum());
        assertTrue(jdbc.queryForObject("SELECT reason FROM mes_wm_stock_quality_hold WHERE arrival_transaction_id=100",
                String.class).contains("余量0"));
    }

    @Test
    void transfersKnownToPrecedeContaminationAreNotDownstream() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.minusHours(1));
        apply();
        assertHeld(2, false);
        assertEquals(1, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void sameSecondEarlierTransferIdsDoNotContaminateTheGoodDestination() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(50, 51, 1, 2, 1, 2, RECEIPT);
        jdbc.update("UPDATE mes_wm_transaction SET transaction_time=? WHERE id=51", RECEIPT);
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertEquals(1, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void sameSecondLaterTransferIdsAreProvenDownstream() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT);
        jdbc.update("UPDATE mes_wm_transaction SET transaction_time=? WHERE id=201", RECEIPT);
        apply();
        assertHeld(1, true);
        assertHeld(2, true);
        assertEquals(2, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void sameSecondReversedPairIsOnlyUnresolvedEvidence() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 199, 1, 2, 1, 2, RECEIPT.plusHours(1));
        jdbc.update("UPDATE mes_wm_transaction SET transaction_time=? WHERE id=199", RECEIPT.plusHours(1));
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertUnresolved(199);
    }

    @Test
    void uncertainTransferTimestampsAreReportedWithoutGuessingDestination() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        jdbc.update("UPDATE mes_wm_transaction SET transaction_time=NULL WHERE id=200");
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertUnresolved(201);
    }

    @Test
    void mismatchedPairAndCrossTenantTargetAreReportedWithoutPropagation() {
        stock(2, 2, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        apply();
        assertHeld(2, false);
        assertUnresolved(201); // Ledger tenant 1 cannot identify tenant 2's stock ID.
        jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        jdbc.update("UPDATE mes_wm_transaction SET quantity=1 WHERE id=201");
        apply();
        assertHeld(2, false);
        assertUnresolved(200); // Broken quantity pairing identifies only the OUT, never its target.
    }

    @Test
    void unpairedHistoricalSaleIsAnExposureReportWithoutAStockGuess() {
        ledger(200, 1, 2, 118, 9200, 10200, 1, null, 10, 7, 1, "-2", RECEIPT.plusHours(1));
        apply();
        assertHeld(1, true);
        assertUnresolved(200);
        assertTrue(jdbc.queryForObject("SELECT reason FROM mes_wm_stock_quality_hold WHERE arrival_transaction_id=200",
                String.class).contains("销售"));
    }

    @Test
    void downstreamQuarantineIsNotMistakenForAnOrdinaryStock() {
        stock(2, 1, 10, 7, 2, "7");
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(2,1,'QUALITY_QUARANTINE_WAREHOUSE',"
                + "'SYSTEM_QUALITY_QUARANTINE',FALSE)");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        apply();
        assertHeld(2, false);
        assertEquals(1, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void repeatedMigrationIsIdempotentAndKeepsOriginalObservation() {
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        apply();
        jdbc.update("UPDATE mes_wm_material_stock SET quantity=13 WHERE id=1");
        apply();
        assertEquals(2, count("mes_wm_stock_quality_hold"));
        assertEquals(0, jdbc.queryForObject("SELECT observed_quantity FROM mes_wm_stock_quality_hold WHERE arrival_transaction_id=100",
                BigDecimal.class).compareTo(new BigDecimal("12.500000")));
        assertEquals(0, quantity(1).compareTo(new BigDecimal("13")));
    }

    @Test
    void evidenceAndHoldsRollbackTogetherWhenLaterMigrationStepFails() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            apply();
            assertHeld(1, true);
            throw new IllegalStateException("simulated later migration failure");
        }));
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
        assertEquals(1, count("mes_wm_transaction"));
        assertEquals(0, quantity(1).compareTo(new BigDecimal("12.500000")));
    }

    @Test
    void confirmedFailIqcHoldsOnlyItsPreciselyLinkedPurchaseStock() {
        purchase();
        stock(2, 1, 10, 7, 2, "8");
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertEquals(110, jdbc.queryForObject("SELECT source_biz_type FROM mes_wm_stock_quality_hold", Integer.class));
        assertEquals(0, quantity(1).compareTo(new BigDecimal("12.500000")));
    }

    @Test
    void deletedPurchaseLineAndIqcStillProveAnActualHistoricalFailReceipt() {
        purchase();
        jdbc.update("UPDATE mes_wm_item_receipt_line SET deleted=TRUE");
        jdbc.update("UPDATE mes_wm_item_receipt SET deleted=TRUE,status=0");
        jdbc.update("UPDATE mes_qc_iqc SET deleted=TRUE");
        apply();
        assertHeld(1, true);
    }

    @Test
    void ordinaryPurchaseWithoutIqcAndConsistentPassedIqcAreNotHistoricalBadStock() {
        purchase();
        jdbc.update("UPDATE mes_wm_item_receipt SET iqc_id=NULL");
        apply();
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
        jdbc.update("UPDATE mes_wm_item_receipt SET iqc_id=810");
        jdbc.update("UPDATE mes_qc_iqc SET check_result=1");
        apply();
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
    }

    @Test
    void unknownOrMismatchedPurchaseIqcIsOnlyReportedForReview() {
        purchase();
        for (String change : new String[]{"status=0", "check_result=0", "item_id=99", "vendor_id=99", "tenant_id=2"}) {
            jdbc.update("UPDATE mes_qc_iqc SET status=4,check_result=2,item_id=10,vendor_id=40,tenant_id=1");
            jdbc.update("UPDATE mes_qc_iqc SET " + change);
            apply();
            assertHeld(1, false);
            assertUnresolved(110);
            jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        }
        jdbc.update("DELETE FROM mes_qc_iqc");
        apply();
        assertHeld(1, false);
        assertUnresolved(110);
    }

    @Test
    void mismatchedPassedPurchaseIqcCannotSilentlyCertifyAnUnrelatedReceipt() {
        purchase();
        jdbc.update("UPDATE mes_qc_iqc SET check_result=1,vendor_id=99");
        apply();
        assertHeld(1, false);
        assertUnresolved(110);
    }

    @Test
    void purchaseParentItemBatchAndTenantMustMatchTheActualLedger() {
        purchase();
        for (String change : new String[]{"receipt_id=999", "item_id=99", "batch_id=999", "batch_id=NULL", "tenant_id=2"}) {
            jdbc.update("UPDATE mes_wm_item_receipt_line SET receipt_id=910,item_id=10,batch_id=7,tenant_id=1");
            jdbc.update("UPDATE mes_wm_item_receipt_line SET " + change);
            apply();
            assertHeld(1, false);
            assertUnresolved(110);
            jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        }
    }

    @Test
    void provenBadPurchasePropagatesOnlyAlongRealLaterWarehouseTransferPair() {
        purchase();
        stock(2, 1, 10, 7, 2, "7");
        transfer(200, 201, 1, 2, 1, 2, RECEIPT.plusHours(1));
        apply();
        assertHeld(1, true);
        assertHeld(2, true);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_stock_quality_hold"
                + " WHERE source_transaction_id=110 AND source_biz_type=110", Integer.class));
    }

    @Test
    void failOutsourceReceiptUsesExactLineHeaderAndStockRatherThanBatchWideHold() {
        outsource();
        stock(2, 1, 10, 7, 2, "8");
        jdbc.update("UPDATE mes_wm_outsource_receipt_line SET deleted=TRUE");
        jdbc.update("UPDATE mes_wm_outsource_receipt SET deleted=TRUE,status=0");
        apply();
        assertHeld(1, true);
        assertHeld(2, false);
        assertEquals(121, jdbc.queryForObject("SELECT source_biz_type FROM mes_wm_stock_quality_hold", Integer.class));
    }

    @Test
    void inconsistentOutsourceSourceAndUnknownQualityAreOnlyReviewEvidence() {
        outsource();
        for (String change : new String[]{"receipt_id=999", "item_id=99", "batch_id=999", "tenant_id=2", "quality_status=0"}) {
            jdbc.update("UPDATE mes_wm_outsource_receipt_line SET receipt_id=921,item_id=10,batch_id=7,tenant_id=1,quality_status=2");
            jdbc.update("UPDATE mes_wm_outsource_receipt_line SET " + change);
            apply();
            assertHeld(1, false);
            assertUnresolved(121);
            jdbc.update("DELETE FROM mes_wm_stock_quality_hold");
        }
        jdbc.update("DELETE FROM mes_wm_outsource_receipt_line");
        apply();
        assertHeld(1, false);
        assertUnresolved(121);
    }

    @Test
    void passedOutsourceAndNewQuarantinePurchaseAreNotHistoricalBadStock() {
        outsource();
        jdbc.update("UPDATE mes_wm_outsource_receipt_line SET quality_status=1");
        apply();
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
        purchase();
        jdbc.update("INSERT INTO mes_wm_warehouse VALUES(1,1,'QUALITY_QUARANTINE_WAREHOUSE',"
                + "'SYSTEM_QUALITY_QUARANTINE',FALSE)");
        apply();
        assertHeld(1, false);
        assertEquals(0, count("mes_wm_stock_quality_hold"));
    }

    private void apply() {
        jdbc.update(block("ROOT"));
        int added;
        int loops = 0;
        do {
            added = jdbc.update(block("PROPAGATE"));
            assertTrue(++loops < 50, "Evidence unique key must make cyclic paths converge");
        } while (added > 0);
        jdbc.update(block("UNRESOLVED"));
        jdbc.update(block("UNPAIRED"));
        jdbc.update(block("HOLD"));
    }

    private String block(String name) {
        String start = "-- QUALITY_HISTORY_" + name + "_BEGIN";
        String end = "-- QUALITY_HISTORY_" + name + "_END";
        int from = migration.indexOf(start);
        int to = migration.indexOf(end);
        assertTrue(from >= 0 && to > from, "Migration must expose its real " + name + " DML");
        return migration.substring(from + start.length(), to).trim();
    }

    private void stock(long id, long tenant, long item, long batch, long warehouse, String quantity) {
        jdbc.update("INSERT INTO mes_wm_material_stock(id,tenant_id,item_id,batch_id,warehouse_id,location_id,area_id,quantity)"
                + " VALUES(?,?,?,?,?,?,?,?)", id, tenant, item, batch, warehouse, warehouse, warehouse, new BigDecimal(quantity));
    }

    private void source(long transactionId, long tenant, int bizType, long stockId, int quality) {
        String parent = bizType == 119 ? "mes_wm_return_sales" : "mes_wm_return_issue";
        String line = bizType == 119 ? "mes_wm_return_sales_line" : "mes_wm_return_issue_line";
        String parentField = bizType == 119 ? "return_id" : "issue_id";
        jdbc.update("INSERT INTO " + parent + "(id,tenant_id) VALUES(900,?)", tenant);
        jdbc.update("INSERT INTO " + line + "(id,tenant_id," + parentField + ",item_id,batch_id,quality_status)"
                + " VALUES(?,?,900,10,7,?)", transactionId + 1000, tenant, quality);
        ledger(transactionId, tenant, 1, bizType, 900, transactionId + 1000, stockId, null,
                10, 7, 1, "2", RECEIPT);
    }

    private void purchase() {
        jdbc.update("DELETE FROM mes_wm_transaction");
        jdbc.update("INSERT INTO mes_wm_item_receipt(id,tenant_id,iqc_id,vendor_id) VALUES(910,1,810,40)");
        jdbc.update("INSERT INTO mes_qc_iqc(id,tenant_id,item_id,vendor_id,status,check_result) VALUES(810,1,10,40,4,2)");
        jdbc.update("INSERT INTO mes_wm_item_receipt_line(id,tenant_id,receipt_id,item_id,batch_id) VALUES(1110,1,910,10,7)");
        ledger(110, 1, 1, 110, 910, 1110, 1, null, 10, 7, 1, "2", RECEIPT);
    }

    private void outsource() {
        jdbc.update("DELETE FROM mes_wm_transaction");
        jdbc.update("INSERT INTO mes_wm_outsource_receipt(id,tenant_id) VALUES(921,1)");
        jdbc.update("INSERT INTO mes_wm_outsource_receipt_line(id,tenant_id,receipt_id,item_id,batch_id,quality_status)"
                + " VALUES(1121,1,921,10,7,2)");
        ledger(121, 1, 1, 121, 921, 1121, 1, null, 10, 7, 1, "2", RECEIPT);
    }

    private void transfer(long outId, long inId, long fromStock, long toStock,
                          long fromWarehouse, long toWarehouse, LocalDateTime time) {
        ledger(outId, 1, 3, 111, outId + 9000, outId + 10000, fromStock, null,
                10, 7, fromWarehouse, "-2", time);
        ledger(inId, 1, 4, 112, outId + 9000, outId + 10000, toStock, outId,
                10, 7, toWarehouse, "2", time.plusSeconds(1));
    }

    private void ledger(long id, long tenant, int type, int bizType, long bizId, long lineId,
                        long stockId, Long related, long item, long batch, long warehouse,
                        String quantity, LocalDateTime time) {
        jdbc.update("INSERT INTO mes_wm_transaction(id,tenant_id,type,biz_type,biz_id,biz_line_id,material_stock_id,"
                        + "related_transaction_id,item_id,batch_id,warehouse_id,location_id,area_id,quantity,transaction_time)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, tenant, type, bizType, bizId, lineId, stockId,
                related, item, batch, warehouse, warehouse, warehouse, new BigDecimal(quantity), time);
    }

    private void assertHeld(long stockId, boolean expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT quality_hold FROM mes_wm_material_stock WHERE id=?",
                Boolean.class, stockId));
        assertEquals(expected, jdbc.queryForObject("SELECT frozen FROM mes_wm_material_stock WHERE id=?",
                Boolean.class, stockId));
    }

    private void assertUnresolved(long arrival) {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_stock_quality_hold"
                + " WHERE arrival_transaction_id=? AND material_stock_id IS NULL AND status='PENDING_REVIEW'",
                Integer.class, arrival));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private BigDecimal quantity(long stock) {
        return jdbc.queryForObject("SELECT quantity FROM mes_wm_material_stock WHERE id=?", BigDecimal.class, stock);
    }
}
