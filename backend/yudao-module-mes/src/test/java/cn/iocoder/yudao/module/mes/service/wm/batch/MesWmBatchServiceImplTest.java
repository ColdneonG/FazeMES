package cn.iocoder.yudao.module.mes.service.wm.batch;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.batch.MesWmBatchMapper;
import cn.iocoder.yudao.module.mes.service.md.autocode.MesMdAutoCodeRecordService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemBatchConfigService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlMergeMode;

import javax.sql.DataSource;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MesWmBatchServiceImpl} 的单元测试
 *
 * @author 芋道源码
 */
@Import(MesWmBatchServiceImpl.class)
@Sql(scripts = {"/sql/clean.sql", "/sql/sn_trace_tables.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
public class MesWmBatchServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MesWmBatchServiceImpl batchService;

    @Resource
    private MesWmBatchMapper batchMapper;

    @MockitoBean
    private MesMdItemService itemService;
    @MockitoBean
    private MesMdItemBatchConfigService itemBatchConfigService;
    @MockitoBean
    private MesMdAutoCodeRecordService autoCodeRecordService;
    @MockitoBean
    private MesWmBarcodeService barcodeService;

    @Resource
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareTraceFixture() {
        jdbc = new JdbcTemplate(dataSource);
        clearConsumption();
        TenantContextHolder.setTenantId(0L);
    }

    @AfterEach
    void clearTraceFixture() {
        clearConsumption();
        jdbc.update("DELETE FROM mes_pro_sn_material");
        jdbc.update("DELETE FROM mes_pro_sn_station");
        jdbc.update("DELETE FROM mes_pro_sn_trace");
        TenantContextHolder.clear();
    }

    private void clearConsumption() {
        jdbc.update("DELETE FROM mes_wm_item_consume_detail");
        jdbc.update("DELETE FROM mes_wm_item_consume_line");
        jdbc.update("DELETE FROM mes_wm_item_consume");
    }

    // ==================== 向前追溯 ====================

    @Test
    public void testGetForwardBatchList_nullCode() {
        // 传入 null code，应返回空列表（不抛异常）
        List<MesWmBatchDO> result = batchService.getForwardBatchList(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetForwardBatchList_noResults() {
        // 传入一个不存在的批次号，XML 查询返回空（因为关联表无数据）
        List<MesWmBatchDO> result = batchService.getForwardBatchList("NOT_EXIST_CODE");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== 向后追溯 ====================

    @Test
    public void testGetBackwardBatchList_nullCode() {
        // 传入 null code，应返回空列表（不抛异常）
        List<MesWmBatchDO> result = batchService.getBackwardBatchList(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetBackwardBatchList_noResults() {
        // 传入一个不存在的批次号，XML 查询返回空
        List<MesWmBatchDO> result = batchService.getBackwardBatchList("NOT_EXIST_CODE");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== 循环检测 ====================

    @Test
    public void testForwardBatchList_sameCodeDoesNotRecurse() {
        // 验证：传入相同 code 多次调用不会 StackOverflow
        // 由于数据库中没有环路数据，这里主要验证 visited set 的 null + 空数据安全
        List<MesWmBatchDO> result = batchService.getForwardBatchList("SAME_CODE");
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // 二次调用同一个 code，不应有任何异常
        List<MesWmBatchDO> result2 = batchService.getForwardBatchList("SAME_CODE");
        assertNotNull(result2);
        assertTrue(result2.isEmpty());
    }

    @Test
    public void testBackwardBatchList_sameCodeDoesNotRecurse() {
        // 验证向后追溯的 visited set 安全
        List<MesWmBatchDO> result = batchService.getBackwardBatchList("SAME_CODE");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== selectFirst ====================

    @Test
    public void testSelectFirst_noMatch() {
        // 查询不存在的 itemId，应返回 null
        MesWmBatchDO query = MesWmBatchDO.builder().itemId(randomLongId()).build();
        MesWmBatchDO result = batchMapper.selectFirst(query);
        assertNull(result);
    }

    @Test
    public void testSelectFirst_returnsSmallestId() {
        // 准备数据：插入两条相同条件的批次
        Long itemId = randomLongId();
        MesWmBatchDO batch1 = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_001").build();
        MesWmBatchDO batch2 = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_002").build();
        batchMapper.insert(batch1);
        batchMapper.insert(batch2);

        // 查询
        MesWmBatchDO query = MesWmBatchDO.builder().itemId(itemId).build();
        MesWmBatchDO result = batchMapper.selectFirst(query);

        // 断言：返回 ID 最小的
        assertNotNull(result);
        assertEquals(batch1.getId(), result.getId());
        assertEquals("BATCH_001", result.getCode());
    }

    @Test
    public void testSelectFirst_nullFieldMatching() {
        // 准备数据：一条有 vendorId，一条没有 vendorId
        Long itemId = randomLongId();
        Long vendorId = randomLongId();

        MesWmBatchDO batchWithVendor = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_WITH_V").vendorId(vendorId).build();
        MesWmBatchDO batchWithoutVendor = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_NO_V").build();
        batchMapper.insert(batchWithVendor);
        batchMapper.insert(batchWithoutVendor);

        // 查询：vendorId 为 null -> 应该只匹配 vendorId IS NULL 的记录
        MesWmBatchDO query = MesWmBatchDO.builder().itemId(itemId).build();
        MesWmBatchDO result = batchMapper.selectFirst(query);

        assertNotNull(result);
        assertEquals("BATCH_NO_V", result.getCode());
        assertNull(result.getVendorId());
    }

    @Test
    public void testSelectFirst_withVendorId() {
        // 准备数据
        Long itemId = randomLongId();
        Long vendorId = randomLongId();

        MesWmBatchDO batchWithVendor = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_V1").vendorId(vendorId).build();
        MesWmBatchDO batchWithoutVendor = MesWmBatchDO.builder()
                .itemId(itemId).code("BATCH_NV").build();
        batchMapper.insert(batchWithVendor);
        batchMapper.insert(batchWithoutVendor);

        // 查询：指定 vendorId -> 只匹配有 vendorId 的
        MesWmBatchDO query = MesWmBatchDO.builder().itemId(itemId).vendorId(vendorId).build();
        MesWmBatchDO result = batchMapper.selectFirst(query);

        assertNotNull(result);
        assertEquals("BATCH_V1", result.getCode());
        assertEquals(vendorId, result.getVendorId());
    }

    // ==================== selectByCode ====================

    @Test
    public void testSelectByCode() {
        // 准备数据
        MesWmBatchDO batch = MesWmBatchDO.builder()
                .itemId(randomLongId()).code("TEST_CODE_001").build();
        batchMapper.insert(batch);

        // 查询
        MesWmBatchDO result = batchMapper.selectByCode("TEST_CODE_001");
        assertNotNull(result);
        assertEquals(batch.getId(), result.getId());

        // 查询不存在的
        MesWmBatchDO notFound = batchMapper.selectByCode("NOT_EXIST");
        assertNull(notFound);
    }

    // ==================== Actual genealogy regression tests ====================

    @Test
    void sameWorkOrderDifferentFeedbacksNeverCrossLinkBatches() {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        completedEdge(20, 100, 0, "RAW-B", "P2");
        assertEquals(List.of("P1"), forward("RAW-A", 0));
        assertEquals(List.of("P2"), forward("RAW-B", 0));
        assertEquals(List.of("RAW-A"), backward("P1", 0));
        assertEquals(List.of("RAW-B"), backward("P2", 0));
    }

    @ParameterizedTest
    @CsvSource({
            "mes_pro_feedback,10,0", "mes_pro_feedback,10,2", "mes_pro_feedback,10,3",
            "mes_pro_feedback,10,5", "mes_wm_item_consume,1010,0",
            "mes_wm_product_produce,2010,0", "mes_wm_product_produce,2010,5"
    })
    void unfinishedCancelledAndAwaitingInspectionDocumentsAreNotActualGenealogy(String table, long id, int status) {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        jdbc.update("UPDATE " + table + " SET status=? WHERE id=?", status, id);
        assertTrue(forward("RAW-A", 0).isEmpty());
        assertTrue(backward("P1", 0).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "mes_wm_item_consume_detail,3010", "mes_wm_item_consume,1010", "mes_pro_feedback,10",
            "mes_wm_product_produce,2010", "mes_wm_product_produce_detail,4010"
    })
    void deletedRowsAtAnyDirectEdgeAreExcluded(String table, long id) {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        jdbc.update("UPDATE " + table + " SET deleted=1 WHERE id=?", id);
        assertTrue(forward("RAW-A", 0).isEmpty());
        assertTrue(backward("P1", 0).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "mes_wm_item_consume_detail,3010", "mes_wm_item_consume,1010", "mes_pro_feedback,10",
            "mes_wm_product_produce,2010", "mes_wm_product_produce_detail,4010"
    })
    void foreignTenantRowsAtAnyDirectEdgeCannotBridgeTheCurrentTenant(String table, long id) {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        jdbc.update("UPDATE " + table + " SET tenant_id=1 WHERE id=?", id);
        assertTrue(forward("RAW-A", 0).isEmpty());
        assertTrue(backward("P1", 0).isEmpty());
        assertTrue(forward("RAW-A", 1).isEmpty());
        assertTrue(backward("P1", 1).isEmpty());
    }

    @Test
    void tenantContextIsRequiredAndOtherTenantsIdenticalBatchCodesAreInvisible() {
        completedEdge(10, 100, 1, "RAW-A", "P1");
        assertTrue(batchService.getForwardBatchList("RAW-A").isEmpty());
        assertTrue(batchService.getBackwardBatchList("P1").isEmpty());
        TenantContextHolder.setTenantId(1L);
        assertEquals(List.of("P1"), codes(batchService.getForwardBatchList("RAW-A")));
        assertEquals(List.of("RAW-A"), codes(batchService.getBackwardBatchList("P1")));
        TenantContextHolder.clear();
        assertThrows(NullPointerException.class, () -> batchService.getForwardBatchList("RAW-A"));
    }

    @ParameterizedTest
    @CsvSource({"mes_wm_item_consume_detail,3010", "mes_wm_product_produce_detail,4010"})
    void zeroOrNegativeDetailQuantityNeverCreatesAnEdge(String table, long id) {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        for (int quantity : List.of(0, -1)) {
            jdbc.update("UPDATE " + table + " SET quantity=? WHERE id=?", quantity, id);
            assertTrue(forward("RAW-A", 0).isEmpty());
            assertTrue(backward("P1", 0).isEmpty());
        }
    }

    @Test
    void sameFeedbackIdCannotBridgeDifferentWorkOrders() {
        completedEdge(10, 100, 0, "RAW-A", "P1");
        jdbc.update("UPDATE mes_wm_product_produce SET work_order_id=999 WHERE id=2010");
        assertTrue(forward("RAW-A", 0).isEmpty());
        assertTrue(backward("P1", 0).isEmpty());
    }

    @Test
    void outputLineWithoutExecutedActualDetailIsNotTraceable() {
        consume(10, 100, 0, "RAW-A");
        jdbc.update("INSERT INTO mes_wm_product_produce(id,work_order_id,feedback_id,status,tenant_id) VALUES(2010,100,10,4,0)");
        jdbc.update("INSERT INTO mes_wm_product_produce_line(id,produce_id,feedback_id,item_id,quantity,batch_id,batch_code) VALUES(5010,2010,10,300,1,6010,'P1')");
        assertTrue(forward("RAW-A", 0).isEmpty());
        assertTrue(backward("P1", 0).isEmpty());
    }

    @Test
    void priorProcessMaterialsAreNotInferredFromWorkOrderWithoutExplicitAllocation() {
        consume(10, 100, 0, "EARLY-RAW");
        completedEdge(20, 100, 0, "LAST-RAW", "P2");
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertEquals(List.of("LAST-RAW"), backward("P2", 0));
    }

    @Test
    void completedSnAllocationProvidesRecordedCrossProcessGenealogy() {
        crossProcessSn("COMPLETED", "P2");
        assertEquals(List.of("P2"), forward("EARLY-RAW", 0));
        assertEquals(List.of("EARLY-RAW", "LAST-RAW"), backward("P2", 0));
        // Direct and SN evidence for the same batch must not duplicate the result.
        jdbc.update("INSERT INTO mes_pro_sn_material(id,trace_id,station_id,feedback_id,consume_id,consume_line_id,consume_detail_id,material_stock_id,item_id,batch_id,batch_code,quantity,tenant_id) VALUES(4,1,2,20,1020,7020,3020,1,200,6020,'LAST-RAW',1,0)");
        assertEquals(List.of("EARLY-RAW", "LAST-RAW"), backward("P2", 0));
    }

    @Test
    void incompleteSnDoesNotInventCrossProcessEdges() {
        crossProcessSn("REGISTERED", "P2");
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertEquals(List.of("LAST-RAW"), backward("P2", 0));
    }

    @Test
    void registeredWorkOrderLotDoesNotReplaceTheOutputStationsActualInventoryBatch() {
        crossProcessSn("COMPLETED", "WORK-ORDER-LOT");
        // Another report of this work order was never visited by this SN.
        completedEdge(30, 100, 0, "UNRELATED-RAW", "P3");
        assertEquals(List.of("P2"), forward("EARLY-RAW", 0));
        assertEquals(List.of("EARLY-RAW", "LAST-RAW"), backward("P2", 0));
        assertEquals(List.of("UNRELATED-RAW"), backward("P3", 0));
        assertTrue(backward("WORK-ORDER-LOT", 0).isEmpty());
        // An unbatched work-order identity likewise cannot hide actual inventory genealogy.
        jdbc.update("UPDATE mes_pro_sn_trace SET product_batch_code=NULL WHERE id=1");
        assertEquals(List.of("P2"), forward("EARLY-RAW", 0));
        assertEquals(List.of("EARLY-RAW", "LAST-RAW"), backward("P2", 0));
        assertEquals(List.of("UNRELATED-RAW"), backward("P3", 0));
    }

    @ParameterizedTest
    @CsvSource({
            "mes_pro_sn_trace,1", "mes_pro_sn_station,1", "mes_pro_sn_station,2", "mes_pro_sn_material,3",
            "mes_pro_feedback,10", "mes_pro_feedback,20", "mes_wm_item_consume,1010",
            "mes_wm_item_consume_detail,3010", "mes_wm_product_produce,2020", "mes_wm_product_produce_detail,4020"
    })
    void deletedRowsAtAnySnEvidenceLinkExcludeTheCrossProcessEdge(String table, long id) {
        crossProcessSn("COMPLETED", "P2");
        jdbc.update("UPDATE " + table + " SET deleted=1 WHERE id=?", id);
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertFalse(backward("P2", 0).contains("EARLY-RAW"));
    }

    @ParameterizedTest
    @CsvSource({
            "mes_pro_sn_trace,1", "mes_pro_sn_station,1", "mes_pro_sn_station,2", "mes_pro_sn_material,3",
            "mes_pro_feedback,10", "mes_pro_feedback,20", "mes_wm_item_consume,1010",
            "mes_wm_item_consume_detail,3010", "mes_wm_product_produce,2020", "mes_wm_product_produce_detail,4020"
    })
    void foreignTenantAtAnySnEvidenceLinkCannotJoinAnotherTenantsHistory(String table, long id) {
        crossProcessSn("COMPLETED", "P2");
        jdbc.update("UPDATE " + table + " SET tenant_id=1 WHERE id=?", id);
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertFalse(backward("P2", 0).contains("EARLY-RAW"));
        assertTrue(forward("EARLY-RAW", 1).isEmpty());
    }

    @Test
    void staleSnMaterialSnapshotDoesNotOverrideActualBatchIdentity() {
        crossProcessSn("COMPLETED", "P2");
        jdbc.update("UPDATE mes_pro_sn_material SET batch_code='ANOTHER-RAW' WHERE id=3");
        assertTrue(forward("ANOTHER-RAW", 0).isEmpty());
        assertTrue(forward("EARLY-RAW", 0).isEmpty());
        assertEquals(List.of("LAST-RAW"), backward("P2", 0));
    }

    @Test
    void recursiveTraceDeduplicatesConvergentPathsAndExcludesItsOwnRoot() {
        completedEdge(10, 100, 0, "A", "P1");
        completedEdge(20, 200, 0, "P1", "P2");
        completedEdge(30, 300, 0, "P2", "A");
        completedEdge(40, 400, 0, "A", "P2");
        assertEquals(java.util.Set.of("P1", "P2"), new java.util.HashSet<>(codes(batchService.getForwardBatchList("A"))));
        assertEquals(3, batchService.getForwardBatchList("A").size()); // P2 has two distinct producing work orders.
        assertEquals(java.util.Set.of("P1", "P2"), new java.util.HashSet<>(codes(batchService.getBackwardBatchList("A"))));
        assertFalse(codes(batchService.getBackwardBatchList("A")).contains("A"));
    }

    private void completedEdge(long feedback, long order, long tenant, String raw, String product) {
        consume(feedback, order, tenant, raw);
        jdbc.update("INSERT INTO mes_wm_product_produce(id,work_order_id,feedback_id,status,tenant_id) VALUES(?,?,?,?,?)",
                2000 + feedback, order, feedback, 4, tenant);
        jdbc.update("INSERT INTO mes_wm_product_produce_detail(id,produce_id,item_id,quantity,batch_id,batch_code,tenant_id) VALUES(?,?,300,1,?,?,?)",
                4000 + feedback, 2000 + feedback, 8000 + feedback, product, tenant);
    }

    private void consume(long feedback, long order, long tenant, String raw) {
        jdbc.update("INSERT INTO mes_pro_feedback(id,work_order_id,item_id,status,tenant_id) VALUES(?,?,300,4,?)",
                feedback, order, tenant);
        jdbc.update("INSERT INTO mes_wm_item_consume(id,work_order_id,task_id,workstation_id,process_id,feedback_id,consume_date,status,tenant_id) VALUES(?,?,1,1,1,?,CURRENT_TIMESTAMP,4,?)",
                1000 + feedback, order, feedback, tenant);
        jdbc.update("INSERT INTO mes_wm_item_consume_detail(id,consume_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,tenant_id) VALUES(?,?,?,200,1,?,?,1,1,1,?)",
                3000 + feedback, 1000 + feedback, 7000 + feedback, 6000 + feedback, raw, tenant);
    }

    private void crossProcessSn(String state, String productBatch) {
        consume(10, 100, 0, "EARLY-RAW");
        completedEdge(20, 100, 0, "LAST-RAW", "P2");
        jdbc.update("INSERT INTO mes_pro_sn_trace(id,sn_id,sn_code,work_order_id,product_id,product_batch_code,status,tenant_id) VALUES(1,1,'SN-1',100,300,?,?,0)",
                productBatch, state);
        for (int station = 1; station <= 2; station++) {
            jdbc.update("INSERT INTO mes_pro_sn_station(id,trace_id,request_key,request_hash,work_order_id,feedback_id,task_id,route_id,process_id,process_sort,operator_id,station_time,tenant_id) VALUES(?,1,?,'hash',100,?,1,1,?,?,1,CURRENT_TIMESTAMP,0)",
                    station, "station-" + station, 10L * station, station, station);
        }
        jdbc.update("INSERT INTO mes_pro_sn_material(id,trace_id,station_id,feedback_id,consume_id,consume_line_id,consume_detail_id,material_stock_id,item_id,batch_id,batch_code,quantity,tenant_id) VALUES(3,1,1,10,1010,7010,3010,1,200,6010,'EARLY-RAW',1,0)");
    }

    private List<String> forward(String code, long tenant) {
        return codes(batchMapper.selectListByForward(code, tenant));
    }

    private List<String> backward(String code, long tenant) {
        return codes(batchMapper.selectListByBackward(code, tenant));
    }

    private static List<String> codes(List<MesWmBatchDO> batches) {
        return batches.stream().map(MesWmBatchDO::getCode).toList();
    }

}
