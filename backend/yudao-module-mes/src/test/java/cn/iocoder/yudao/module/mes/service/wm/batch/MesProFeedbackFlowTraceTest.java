package cn.iocoder.yudao.module.mes.service.wm.batch;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.batch.MesWmBatchMapper;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemBatchConfigService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.pro.feedback.MesProFeedbackFlowService;
import cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlMergeMode;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Actual mapper regression for evidence-backed genealogy across manufacturing processes. */
@Sql(scripts = {"/sql/clean.sql", "/sql/sn_trace_tables.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
@Import(MesWmBatchServiceImpl.class)
class MesProFeedbackFlowTraceTest extends BaseDbUnitTest {

    @Resource
    private DataSource dataSource;
    @Resource
    private MesWmBatchMapper batchMapper;
    @Resource
    private MesWmBatchServiceImpl batchService;
    @MockitoBean
    private MesMdItemService itemService;
    @MockitoBean
    private MesMdItemBatchConfigService itemBatchConfigService;
    @MockitoBean
    private MesMdAutoCodeRecordService autoCodeRecordService;
    @MockitoBean
    private MesWmBarcodeService barcodeService;
    private JdbcTemplate jdbc;
    private MesProFeedbackFlowService flowService;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        flowService = new MesProFeedbackFlowService();
        ReflectionTestUtils.setField(flowService, "jdbc", jdbc);
        ReflectionTestUtils.setField(flowService, "batchMapper", batchMapper);
        TenantContextHolder.setTenantId(0L);
        jdbc.execute("ALTER TABLE mes_pro_feedback ADD COLUMN IF NOT EXISTS flow_required bit DEFAULT 0");
        jdbc.execute("ALTER TABLE mes_pro_feedback ADD COLUMN IF NOT EXISTS flow_complete bit DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_pro_feedback_source (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, feedback_id bigint NOT NULL, source_feedback_id bigint NOT NULL, quantity decimal(20,6) NOT NULL, frozen bit NOT NULL DEFAULT 0, created_by varchar(64), created_at timestamp DEFAULT CURRENT_TIMESTAMP, frozen_at timestamp, deleted bit NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_pro_feedback_ancestor (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, feedback_id bigint NOT NULL, ancestor_feedback_id bigint NOT NULL, deleted bit NOT NULL DEFAULT 0)");
        clearEvidence();
    }

    @AfterEach
    void clear() {
        clearEvidence();
        TenantContextHolder.clear();
    }

    @Test
    void completedExplicitProcessAllocationTracesEarlierConsumptionToFinalOutput() {
        feedback(10, 100, 300, 0);
        consume(10, 100, 200, "EARLY-RAW", 0);
        feedback(20, 100, 300, 0);
        consume(20, 100, 201, "FINAL-PACKAGING", 0);
        produce(20, 100, 300, "FINISHED-LOT", 0);
        allocated(20, 10, 0);

        assertEquals(List.of("EARLY-RAW", "FINAL-PACKAGING"), backward("FINISHED-LOT", 0));
        assertEquals(List.of("FINISHED-LOT"), forward("EARLY-RAW", 0));
    }

    @Test
    void sameWorkOrderBranchesRemainSeparateAndAnExplicitMergeContainsBothParents() {
        feedback(10, 100, 300, 0);
        consume(10, 100, 200, "RAW-A", 0);
        feedback(20, 100, 300, 0);
        consume(20, 100, 200, "RAW-B", 0);
        feedback(30, 100, 300, 0);
        produce(30, 100, 300, "PRODUCT-A", 0);
        allocated(30, 10, 0);
        feedback(40, 100, 300, 0);
        produce(40, 100, 300, "PRODUCT-B", 0);
        allocated(40, 20, 0);
        feedback(50, 100, 300, 0);
        produce(50, 100, 300, "PRODUCT-MERGE", 0);
        allocated(50, 10, 0);
        allocated(50, 20, 0);
        feedback(60, 100, 300, 0);
        consume(60, 100, 200, "UNALLOCATED-RAW", 0);
        jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=2,qualified_quantity=2 WHERE id IN(10,20,50)");
        jdbc.update("UPDATE mes_pro_feedback SET process_id=10 WHERE id IN(10,20,60)");
        jdbc.update("UPDATE mes_pro_feedback SET process_id=20 WHERE id IN(30,40,50)");
        jdbc.update("UPDATE mes_wm_item_consume_detail SET quantity=2 WHERE consume_id IN(1010,1020)");
        jdbc.update("UPDATE mes_wm_product_produce_detail SET quantity=2 WHERE produce_id=2050");

        assertEquals(List.of("RAW-A"), backward("PRODUCT-A", 0));
        assertEquals(List.of("RAW-B"), backward("PRODUCT-B", 0));
        assertEquals(List.of("RAW-A", "RAW-B"), backward("PRODUCT-MERGE", 0));
        assertEquals(List.of("PRODUCT-A", "PRODUCT-MERGE"), forward("RAW-A", 0));
        assertTrue(forward("UNALLOCATED-RAW", 0).isEmpty());
    }

    @Test
    void aSplitUsesOneRecordedParentForEachChildWithoutInventingSiblingEdges() {
        feedback(10, 100, 300, 0);
        consume(10, 100, 200, "COMMON-RAW", 0);
        feedback(20, 100, 300, 0);
        consume(20, 100, 201, "BRANCH-A-RAW", 0);
        feedback(30, 100, 300, 0);
        consume(30, 100, 201, "BRANCH-B-RAW", 0);
        allocated(20, 10, 0);
        allocated(30, 10, 0);
        produce(20, 100, 300, "PRODUCT-A", 0);
        produce(30, 100, 300, "PRODUCT-B", 0);
        jdbc.update("UPDATE mes_pro_feedback SET feedback_quantity=2,qualified_quantity=2 WHERE id=10");
        jdbc.update("UPDATE mes_pro_feedback SET process_id=20 WHERE id=30");
        jdbc.update("UPDATE mes_wm_item_consume_detail SET quantity=2 WHERE consume_id=1010");

        assertEquals(List.of("BRANCH-A-RAW", "COMMON-RAW"), backward("PRODUCT-A", 0));
        assertEquals(List.of("BRANCH-B-RAW", "COMMON-RAW"), backward("PRODUCT-B", 0));
        assertEquals(List.of("PRODUCT-A", "PRODUCT-B"), forward("COMMON-RAW", 0));
        assertEquals(List.of("PRODUCT-A"), forward("BRANCH-A-RAW", 0));
    }

    @ParameterizedTest
    @CsvSource({
            "mes_pro_feedback,10", "mes_pro_feedback,20", "mes_pro_feedback_ancestor,2010",
            "mes_pro_feedback_source,2010", "mes_wm_item_consume,1010",
            "mes_wm_item_consume_detail,3010", "mes_wm_product_produce,2020",
            "mes_wm_product_produce_detail,4020"
    })
    void deletedEvidenceAtAnyNewFlowLinkCannotCreateGenealogy(String table, long id) {
        crossProcess();
        jdbc.update("UPDATE " + table + " SET deleted=1 WHERE id=?", id);
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "mes_pro_feedback,10", "mes_pro_feedback,20", "mes_pro_feedback_ancestor,2010",
            "mes_pro_feedback_source,2010", "mes_wm_item_consume,1010",
            "mes_wm_item_consume_detail,3010", "mes_wm_product_produce,2020",
            "mes_wm_product_produce_detail,4020"
    })
    void foreignTenantAtAnyNewFlowLinkCannotBridgeTenantHistory(String table, long id) {
        crossProcess();
        jdbc.update("UPDATE " + table + " SET tenant_id=1 WHERE id=?", id);
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertTrue(forward("EARLY-RAW", 1).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"mes_pro_feedback,10", "mes_pro_feedback,20", "mes_wm_item_consume,1010", "mes_wm_product_produce,2020"})
    void unfinishedActualDocumentsCannotBeUsedByAncestorEvidence(String table, long id) {
        crossProcess();
        for (int status : List.of(0, 2, 3, 5)) {
            jdbc.update("UPDATE " + table + " SET status=? WHERE id=?", status, id);
            assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
            assertTrue(forward("EARLY-RAW", 0).isEmpty());
        }
    }

    @Test
    void draftSourcesAndOrphanedAncestorsDoNotEstablishACompletedFlow() {
        crossProcess();
        jdbc.update("UPDATE mes_pro_feedback_source SET frozen=0");
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        jdbc.update("DELETE FROM mes_pro_feedback_source");
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
    }

    @ParameterizedTest
    @CsvSource({"mes_wm_item_consume_detail,3010", "mes_wm_product_produce_detail,4020", "mes_pro_feedback_source,2010"})
    void onlyPositiveActualQuantitiesMayEstablishAncestorEdges(String table, long id) {
        crossProcess();
        jdbc.update("UPDATE " + table + " SET quantity=0.000001 WHERE id=?", id);
        assertTrue(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        for (int quantity : List.of(0, -1)) {
            if (table.equals("mes_pro_feedback_source")) {
                assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE " + table + " SET quantity=? WHERE id=?", quantity, id));
                assertTrue(backward("FINISHED-LOT", 0).contains("EARLY-RAW"),
                        "Database rejects the invalid quantity and preserves the original positive evidence");
                continue;
            }
            jdbc.update("UPDATE " + table + " SET quantity=? WHERE id=?", quantity, id);
            assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
            assertTrue(forward("EARLY-RAW", 0).isEmpty());
        }
    }

    @Test
    void anAncestorMustHaveTheSameWorkOrderAndManufacturedItem() {
        crossProcess();
        jdbc.update("UPDATE mes_pro_feedback SET work_order_id=999 WHERE id=10");
        jdbc.update("UPDATE mes_wm_item_consume SET work_order_id=999 WHERE id=1010");
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        jdbc.update("UPDATE mes_pro_feedback SET work_order_id=100,item_id=999 WHERE id=10");
        jdbc.update("UPDATE mes_wm_item_consume SET work_order_id=100 WHERE id=1010");
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
    }

    @Test
    void anAncestorFromAnotherRouteCannotBridgeEvenTheSameWorkOrderAndProduct() {
        crossProcess();
        jdbc.update("UPDATE mes_pro_feedback SET route_id=999 WHERE id=10");
        assertFalse(backward("FINISHED-LOT", 0).contains("EARLY-RAW"));
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
    }

    @Test
    void sharedInventoryBatchIsReportedAsABatchPoolAcrossItsActualProducingRuns() {
        feedback(10, 100, 300, 0);
        consume(10, 100, 200, "RAW-A", 0);
        produce(10, 100, 300, "SHARED-SA", 0);
        feedback(20, 200, 300, 0);
        consume(20, 200, 200, "RAW-B", 0);
        produce(20, 200, 300, "SHARED-SA", 0);
        feedback(30, 300, 400, 0);
        consume(30, 300, 300, "SHARED-SA", 0);
        produce(30, 300, 400, "FINAL", 0);

        assertEquals(Set.of("SHARED-SA", "RAW-A", "RAW-B"),
                new HashSet<>(codes(batchService.getBackwardBatchList("FINAL"))));
        assertEquals(Set.of("SHARED-SA", "FINAL"),
                new HashSet<>(codes(batchService.getForwardBatchList("RAW-A"))));
        assertTrue(flowService.coverage("FINAL").isComplete());
        jdbc.update("UPDATE mes_pro_feedback SET flow_required=0,flow_complete=0 WHERE id=20");
        var coverage = flowService.coverage("FINAL");
        assertFalse(coverage.isComplete(), "Any unverified producer in the shared batch pool prevents a complete claim");
        assertEquals(List.of(20L), coverage.getLegacyFeedbackIds());
    }

    @Test
    void anEarlyLegacyReportWithoutAnyInventoryOutputMakesTheFinalCoverageIncomplete() {
        crossProcess();
        assertTrue(flowService.coverage("FINISHED-LOT").isComplete());
        jdbc.update("UPDATE mes_pro_feedback SET flow_required=0,flow_complete=0 WHERE id=10");
        jdbc.update("UPDATE mes_pro_feedback SET flow_complete=0 WHERE id=20");
        var coverage = flowService.coverage("FINISHED-LOT");
        assertFalse(coverage.isComplete());
        assertEquals(List.of(10L), coverage.getLegacyFeedbackIds());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_product_produce WHERE feedback_id=10", Integer.class));
    }

    @Test
    void exceedingTwentyBatchLevelsExplicitlyMarksTheCoverageTruncated() {
        for (int number = 1; number <= 23; number++) {
            long report = 100 + number;
            feedback(report, report, 300, 0);
            consume(report, report, number == 1 ? 200 : 300, "CHAIN-" + (number - 1), 0);
            produce(report, report, 300, "CHAIN-" + number, 0);
        }
        var coverage = flowService.coverage("CHAIN-23");
        assertFalse(coverage.isComplete());
        assertTrue(coverage.isTruncated());
        assertTrue(coverage.getLegacyFeedbackIds().isEmpty());
    }

    @Test
    void actualBrokyThreeLevelMasterElevenProcessesTraceAllTwentyEightPurchasedMaterials() throws Exception {
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/sql/broky_flow_master.json")) {
            assertNotNull(input);
            fixture = new ObjectMapper().readTree(input);
        }
        Path generator = findRepositoryFile(fixture.get("generator").asText());
        assertEquals(fixture.get("sha256").asText(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(generator))),
                "Regenerate the fixture from the real Broky generator after approved master-data changes");
        JsonNode tables = fixture.get("tables");
        assertEquals(31, tables.get("mes_md_item").size());
        assertEquals(30, tables.get("mes_md_product_bom").size());
        assertEquals(11, tables.get("mes_pro_route_process").size());
        assertEquals(28, fixture.get("purchasedMaterials").size());

        // Build executions using the actual route/material assignments, including the two
        // empty-inspection end processes. Only their final reports create product inventory.
        for (JsonNode routeProduct : tables.get("mes_pro_route_product")) {
            long route = routeProduct.get("route_id").asLong();
            long item = routeProduct.get("item_id").asLong();
            long order = 10_000_000L + item;
            List<JsonNode> processes = StreamSupport.stream(tables.get("mes_pro_route_process").spliterator(), false)
                    .filter(row -> row.get("route_id").asLong() == route)
                    .sorted(Comparator.comparingInt(row -> row.get("sort").asInt())).toList();
            List<Long> ancestors = new ArrayList<>();
            for (JsonNode process : processes) {
                long report = process.get("process_id").asLong();
                feedback(report, order, item, 0);
                List<JsonNode> materials = StreamSupport.stream(tables.get("mes_pro_route_product_bom").spliterator(), false)
                        .filter(row -> row.get("route_id").asLong() == route && row.get("process_id").asLong() == report).toList();
                if (!materials.isEmpty()) {
                    jdbc.update("INSERT INTO mes_wm_item_consume(id,work_order_id,task_id,workstation_id,process_id,feedback_id,consume_date,status,tenant_id) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,4,0)",
                            1000 + report, order, report, report, report, report);
                    for (int index = 0; index < materials.size(); index++) {
                        JsonNode material = materials.get(index);
                        long rawItem = material.get("item_id").asLong();
                        jdbc.update("INSERT INTO mes_wm_item_consume_detail(id,consume_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,tenant_id) VALUES(?,?,?,?,?,?,?,1,1,1,0)",
                                100_000_000L + report * 100 + index, 1000 + report, 7000 + report,
                                rawItem, material.get("quantity").decimalValue(), rawItem, brokyBatch(rawItem));
                    }
                }
                if (!ancestors.isEmpty()) {
                    long immediate = ancestors.get(ancestors.size() - 1);
                    allocated(report, immediate, 0);
                    for (long ancestor : ancestors.subList(0, ancestors.size() - 1)) {
                        jdbc.update("INSERT INTO mes_pro_feedback_ancestor(id,tenant_id,feedback_id,ancestor_feedback_id) VALUES(?,0,?,?)",
                                report * 1_000_000L + ancestor, report, ancestor);
                    }
                }
                if (process.get("key_flag").asBoolean()) {
                    produce(report, order, item, brokyBatch(item), 0);
                }
                ancestors.add(report);
            }
        }

        long finished = fixture.get("finishedProduct").asLong();
        Set<String> expected = new TreeSet<>();
        for (JsonNode material : fixture.get("purchasedMaterials")) {
            String batch = brokyBatch(material.asLong());
            expected.add(batch);
            assertTrue(codes(batchService.getForwardBatchList(batch)).contains(brokyBatch(finished)),
                    "Every actual purchased leaf must trace forward through its parent assembly: " + batch);
        }
        for (JsonNode product : fixture.get("selfMadeProducts")) {
            if (product.asLong() != finished) {
                expected.add(brokyBatch(product.asLong()));
            }
        }
        assertEquals(expected, new TreeSet<>(codes(batchService.getBackwardBatchList(brokyBatch(finished)))));
        assertEquals(30, expected.size(), "28 purchased leaves plus two self-made components");
        assertTrue(flowService.coverage(brokyBatch(finished)).isComplete(),
                "All eleven real manufacturing processes have complete recorded transfer evidence");
        // An early semi-finished report has no inventory output, but its missing historical
        // transfer still invalidates the finished parent's all-level completeness claim.
        jdbc.update("UPDATE mes_pro_feedback SET flow_required=0,flow_complete=0 WHERE id=972106");
        jdbc.update("UPDATE mes_pro_feedback SET flow_complete=0 WHERE id IN(972108,972105)");
        var coverage = flowService.coverage(brokyBatch(finished));
        assertFalse(coverage.isComplete());
        assertEquals(List.of(972106L), coverage.getLegacyFeedbackIds());
    }

    private void crossProcess() {
        feedback(10, 100, 300, 0);
        consume(10, 100, 200, "EARLY-RAW", 0);
        feedback(20, 100, 300, 0);
        consume(20, 100, 201, "FINAL-PACKAGING", 0);
        produce(20, 100, 300, "FINISHED-LOT", 0);
        allocated(20, 10, 0);
    }

    private static Path findRepositoryFile(String relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Cannot find checked-in master generator: " + relative);
    }

    private static String brokyBatch(long item) {
        return "BROKY-ITEM-" + item + "-LOT";
    }

    private void clearEvidence() {
        jdbc.update("DELETE FROM mes_pro_feedback_ancestor");
        jdbc.update("DELETE FROM mes_pro_feedback_source");
        jdbc.update("DELETE FROM mes_wm_item_consume_detail");
        jdbc.update("DELETE FROM mes_wm_item_consume_line");
        jdbc.update("DELETE FROM mes_wm_item_consume");
    }

    private void feedback(long id, long order, long item, long tenant) {
        jdbc.update("INSERT INTO mes_pro_feedback(id,work_order_id,item_id,route_id,process_id,feedback_quantity,qualified_quantity,unqualified_quantity,status,tenant_id,flow_required,flow_complete) VALUES(?,?,?,?,?,1,1,0,4,?,1,1)", id, order, item, item, id, tenant);
    }

    private void consume(long feedback, long order, long item, String batch, long tenant) {
        jdbc.update("INSERT INTO mes_wm_item_consume(id,work_order_id,task_id,workstation_id,process_id,feedback_id,consume_date,status,tenant_id) VALUES(?,?,1,1,1,?,CURRENT_TIMESTAMP,4,?)", 1000 + feedback, order, feedback, tenant);
        jdbc.update("INSERT INTO mes_wm_item_consume_detail(id,consume_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,tenant_id) VALUES(?,?,?,?,1,?,?,1,1,1,?)", 3000 + feedback, 1000 + feedback, 7000 + feedback, item, 6000 + feedback, batch, tenant);
    }

    private void produce(long feedback, long order, long item, String batch, long tenant) {
        jdbc.update("INSERT INTO mes_wm_product_produce(id,work_order_id,feedback_id,status,tenant_id) VALUES(?,?,?,4,?)", 2000 + feedback, order, feedback, tenant);
        jdbc.update("INSERT INTO mes_wm_product_produce_detail(id,produce_id,item_id,quantity,batch_id,batch_code,tenant_id) VALUES(?,?,?,1,?,?,?)", 4000 + feedback, 2000 + feedback, item, 8000 + feedback, batch, tenant);
    }

    private void allocated(long feedback, long source, long tenant) {
        jdbc.update("INSERT INTO mes_pro_feedback_source(id,tenant_id,feedback_id,source_feedback_id,quantity,frozen,frozen_at) VALUES(?,?,?,?,1,1,CURRENT_TIMESTAMP)", feedback * 100 + source, tenant, feedback, source);
        jdbc.update("INSERT INTO mes_pro_feedback_ancestor(id,tenant_id,feedback_id,ancestor_feedback_id) VALUES(?,?,?,?)", feedback * 100 + source, tenant, feedback, source);
    }

    private List<String> backward(String batch, long tenant) {
        return batchMapper.selectListByBackward(batch, tenant).stream().map(MesWmBatchDO::getCode).toList();
    }

    private List<String> forward(String batch, long tenant) {
        return batchMapper.selectListByForward(batch, tenant).stream().map(MesWmBatchDO::getCode).toList();
    }

    private static List<String> codes(List<MesWmBatchDO> batches) {
        return batches.stream().map(MesWmBatchDO::getCode).toList();
    }
}
