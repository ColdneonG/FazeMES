package cn.iocoder.yudao.module.mes.service.pro.sntrace;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemconsume.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.sn.MesWmSnDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.route.MesProRouteProcessMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sntrace.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemconsume.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.materialstock.MesWmMaterialStockMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.sn.MesWmSnMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.transaction.MesWmTransactionMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.warehouse.MesWmWarehouseMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmItemConsumeStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeService;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.annotation.Resource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import java.math.BigDecimal;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 snapshot persistence and allocation balances; existing MES sources are controlled fixtures. */
@Import(MesProSnTraceServiceImpl.class)
@Sql(scripts = "/sql/sn_trace_tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MesProSnTraceServiceImplTest extends BaseDbUnitTest {
    @Resource private MesProSnTraceService service;
    @Resource private MesProSnTraceMapper traceMapper;
    @Resource private MesProSnStationMapper stationMapper;
    @Resource private MesProSnMaterialMapper materialMapper;
    @Resource private DataSource dataSource;
    @Resource private SqlSessionFactory sqlSessionFactory;
    private JdbcTemplate jdbc;
    @MockitoBean private MesWmSnMapper snMapper;
    @MockitoBean private MesProWorkOrderMapper workOrderMapper;
    @MockitoBean private MesProFeedbackMapper feedbackMapper;
    @MockitoBean private MesProTaskMapper taskMapper;
    @MockitoBean private MesProRouteProcessMapper routeProcessMapper;
    @MockitoBean private MesWmItemConsumeMapper consumeMapper;
    @MockitoBean private MesWmItemConsumeLineMapper lineMapper;
    @MockitoBean private MesWmItemConsumeDetailMapper detailMapper;
    @MockitoBean private MesWmMaterialStockMapper stockMapper;
    @MockitoBean private MesWmTransactionMapper transactionMapper;
    @MockitoBean private MesWmWarehouseMapper warehouseMapper;
    @MockitoBean private MesWmBarcodeService barcodeService;
    private MesProFeedbackDO feedback;
    private MesProSnTraceDO trace;
    private MesWmItemConsumeDetailDO first;
    private MesWmItemConsumeDetailDO second;
    private List<MesWmTransactionDO> movements;

    @BeforeEach
    void prepare() {
        // BaseDbUnitTest supplies a DataSource, but does not import JDBC template
        // auto-configuration. Reuse that transactional DataSource explicitly.
        jdbc = new JdbcTemplate(dataSource);
        // A Mockito-replaced mapper is never initialized by MyBatis. The IN
        // wrapper resolves columns eagerly, so register its entity metadata
        // against this real test configuration before exercising the SN lock.
        MapperBuilderAssistant snMetadata = new MapperBuilderAssistant(sqlSessionFactory.getConfiguration(), "sn-trace-test");
        snMetadata.setCurrentNamespace(MesWmSnMapper.class.getName());
        TableInfoHelper.initTableInfo(snMetadata, MesWmSnDO.class);
        TenantContextHolder.setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser().setId(99L), null, List.of()));
        feedback = new MesProFeedbackDO().setId(10L).setCode("FB-10").setWorkOrderId(30L).setItemId(20L)
                .setTaskId(40L).setRouteId(50L).setProcessId(60L).setWorkstationId(70L).setFeedbackUserId(80L)
                .setFeedbackQuantity(new BigDecimal("2")).setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus());
        when(feedbackMapper.selectByIdForUpdate(10L)).thenReturn(feedback);
        when(feedbackMapper.selectById(10L)).thenReturn(feedback);
        when(taskMapper.selectById(40L)).thenReturn(new MesProTaskDO().setId(40L).setWorkOrderId(30L)
                .setItemId(20L).setRouteId(50L).setProcessId(60L).setWorkstationId(70L));
        when(routeProcessMapper.selectListByRouteId(50L)).thenReturn(List.of(
                new MesProRouteProcessDO().setProcessId(60L).setSort(10), new MesProRouteProcessDO().setProcessId(61L).setSort(20)));
        when(consumeMapper.selectByFeedbackId(10L)).thenReturn(new MesWmItemConsumeDO().setId(100L).setFeedbackId(10L)
                .setWorkOrderId(30L).setTaskId(40L).setProcessId(60L).setWorkstationId(70L)
                .setStatus(MesWmItemConsumeStatusEnum.FINISHED.getStatus()));
        when(lineMapper.selectListByConsumeId(100L)).thenReturn(List.of(new MesWmItemConsumeLineDO().setId(110L)
                .setConsumeId(100L).setItemId(200L).setQuantity(new BigDecimal("4"))));
        first = detail(120L, 130L, 140L, "RAW-1", "1"); second = detail(121L, 131L, 141L, "RAW-2", "3");
        when(detailMapper.selectListByConsumeId(100L)).thenReturn(List.of(first, second));
        when(warehouseMapper.selectByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE)).thenReturn(new MesWmWarehouseDO().setId(150L));
        when(stockMapper.selectById(130L)).thenReturn(stock(130L, 140L, "RAW-1"));
        when(stockMapper.selectById(131L)).thenReturn(stock(131L, 141L, "RAW-2"));
        movements = List.of(movement(130L, 140L, "RAW-1", "-1"), movement(131L, 141L, "RAW-2", "-3"));
        when(transactionMapper.selectList(any(LambdaQueryWrapperX.class))).thenAnswer(i -> movements);
        trace = trace("SN-1", 1L, 1L);
    }

    @AfterEach
    void cleanupContext() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void splitBatchAllocationPersistsActualSourcesAndRetriesOnce() {
        MesProSnTraceStationReqVO request = request(trace.getId(), "once", "1", "1");
        Long id = service.station(request);
        assertEquals(id, service.station(request));
        MesProSnTraceRespVO result = service.get(trace.getId());
        assertEquals(1, result.getStations().size());
        assertEquals(99L, result.getStations().get(0).getOperatorId());
        assertEquals(80L, result.getStations().get(0).getFeedbackUserId());
        assertEquals(Set.of("RAW-1", "RAW-2"), new HashSet<>(result.getStations().get(0).getMaterials()
                .stream().map(MesProSnMaterialDO::getBatchCode).toList()));
        assertEquals(1, service.affected(140L, null).size());
        assertEquals(new BigDecimal("2.000000"), result.getStations().get(0).getMaterials().stream()
                .map(MesProSnMaterialDO::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        verify(stockMapper, never()).updateById(any(MesWmMaterialStockDO.class));
        verify(transactionMapper, never()).insert(any(MesWmTransactionDO.class));
    }

    @Test
    void consumesRemainingShareAndRejectsAggregateOverAllocation() {
        service.station(request(trace.getId(), "first", "1", "1"));
        MesProSnTraceDO next = trace("SN-2", 2L, 1L);
        // RAW-1 is exhausted even though RAW-2 still has capacity.
        assertThrows(ServiceException.class, () -> service.station(request(next.getId(), "bad", "1", "1")));
        assertTrue(service.get(next.getId()).getStations().isEmpty());
        service.station(request(next.getId(), "next", "0", "2"));
        assertTrue(service.consumeOptions(10L).stream().allMatch(o -> o.getAvailableQuantity().signum() == 0));
        MesProSnTraceDO excess = trace("SN-3", 3L, 1L);
        assertThrows(ServiceException.class, () -> service.station(request(excess.getId(), "excess", "0", "2")));
        assertEquals(2L, stationMapper.selectCount());
        assertEquals(3L, materialMapper.selectCount());
    }

    @Test
    void rejectsMissingWrongAndPartialConsumptionWithoutLeavingSnapshot() {
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "short", "0", "1")));
        MesProSnTraceStationReqVO wrong = request(trace.getId(), "wrong", "0", "2");
        wrong.getMaterials().get(0).setConsumeDetailId(999L);
        assertThrows(ServiceException.class, () -> service.station(wrong));
        first.setItemId(999L);
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "mismatch", "1", "1")));
        assertEquals(0L, stationMapper.selectCount());
    }

    @Test
    void rejectsSourceWithoutActualMovementOrFromAnotherWorkOrder() {
        movements = List.of();
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "no-out", "1", "1")));
        movements = List.of(movement(130L, 140L, "RAW-1", "-1"), movement(131L, 141L, "RAW-2", "-3"));
        when(stockMapper.selectById(130L)).thenReturn(stock(130L, 140L, "RAW-1").setWorkOrderId(999L));
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "other-order", "1", "1")));
        assertEquals(0L, materialMapper.selectCount());
    }

    @Test
    void rejectsCrossOrderUnapprovedAndFractionalOutputFeedback() {
        feedback.setWorkOrderId(999L);
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "other", "1", "1")));
        feedback.setWorkOrderId(30L).setStatus(MesProFeedbackStatusEnum.APPROVING.getStatus());
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "draft", "1", "1")));
        feedback.setStatus(MesProFeedbackStatusEnum.FINISHED.getStatus()).setFeedbackQuantity(new BigDecimal("1.5"));
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "fraction", "1", "1")));
        assertEquals(0L, stationMapper.selectCount());
    }

    @Test
    void rejectsSkippedOrDuplicateProcessAndChangedIdempotencyPayload() {
        when(routeProcessMapper.selectListByRouteId(50L)).thenReturn(List.of(
                new MesProRouteProcessDO().setProcessId(59L).setSort(5), new MesProRouteProcessDO().setProcessId(60L).setSort(10)));
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "skip", "1", "1")));
        when(routeProcessMapper.selectListByRouteId(50L)).thenReturn(List.of(new MesProRouteProcessDO().setProcessId(60L).setSort(10)));
        service.station(request(trace.getId(), "ok", "1", "1"));
        assertEquals("COMPLETED", service.get(trace.getId()).getSn().getStatus());
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "ok", "0", "2")));
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "duplicate", "1", "1")));
        assertEquals(1L, stationMapper.selectCount());
    }

    @Test
    void tenantBoundaryHidesTraceAndReverseResultsAndBlocksWrites() {
        service.station(request(trace.getId(), "tenant-one", "1", "1"));
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class, () -> service.get(trace.getId()));
        assertThrows(ServiceException.class, () -> service.station(request(trace.getId(), "tenant-two", "1", "1")));
        assertTrue(service.affected(140L, null).isEmpty());
        assertEquals(0L, service.page(new MesProSnTracePageReqVO()).getTotal());
    }

    @Test
    void existingSnRegistrationIsIdempotentAndCannotChangeIdentityOrBeDeleted() {
        when(workOrderMapper.selectByIdForUpdate(30L)).thenReturn(new MesProWorkOrderDO().setId(30L).setProductId(20L).setBatchCode("PB"));
        MesWmSnDO sn = new MesWmSnDO().setId(500L).setCode("EXISTING").setItemId(20L).setBatchCode("PB");
        when(snMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(sn));
        MesProSnTraceRegisterReqVO request = new MesProSnTraceRegisterReqVO().setWorkOrderId(30L).setSnId(500L);
        Long id = service.register(request);
        assertEquals(id, service.register(request));
        assertEquals(20L, service.get(id).getSn().getProductId());
        ServiceException deletionRejected = assertThrows(ServiceException.class, () -> service.ensureSnUntracked(List.of(500L)));
        assertEquals(SnTraceErrorConstants.REFERENCED_SN.getCode(), deletionRejected.getCode());
        assertNotNull(traceMapper.selectById(id));
        assertDoesNotThrow(() -> service.ensureSnUntracked(List.of(999L)));
        sn.setItemId(999L);
        assertThrows(ServiceException.class, () -> service.register(request));
        verifyNoInteractions(barcodeService);
    }

    @Test
    void unbatchedWorkOrderAndExistingSnRegisterAndStationWithoutInventingBatch() {
        when(workOrderMapper.selectByIdForUpdate(30L)).thenReturn(new MesProWorkOrderDO().setId(30L).setProductId(20L));
        MesWmSnDO sn = new MesWmSnDO().setId(500L).setCode("UNBATCHED-SN").setItemId(20L);
        when(snMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(sn));
        MesProSnTraceRegisterReqVO registration = new MesProSnTraceRegisterReqVO().setWorkOrderId(30L).setSnId(500L);
        Long id = service.register(registration);
        assertEquals(id, service.register(registration));
        assertNull(service.get(id).getSn().getProductBatchCode());
        service.station(request(id, "unbatched-product", "1", "1"));
        MesProSnTraceRespVO result = service.get(id);
        assertNull(result.getSn().getProductBatchCode());
        assertEquals(1, result.getStations().size());
        assertEquals(2, result.getStations().get(0).getMaterials().size());
        assertTrue(result.getStations().get(0).getMaterials().stream().allMatch(m -> m.getBatchCode().startsWith("RAW-")));
        assertNull(sn.getBatchCode());
        verifyNoInteractions(barcodeService);
    }

    @Test
    void unbatchedWorkOrderCreatesNewSnAndPreservesNullBatchThroughStation() {
        when(workOrderMapper.selectByIdForUpdate(30L)).thenReturn(new MesProWorkOrderDO().setId(30L).setProductId(20L));
        when(snMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            MesWmSnDO sn = invocation.getArgument(0);
            assertNull(sn.getBatchCode());
            sn.setId(501L);
            return 1;
        }).when(snMapper).insert(any(MesWmSnDO.class));
        Long id = service.register(new MesProSnTraceRegisterReqVO().setWorkOrderId(30L).setSnCode("NEW-UNBATCHED-SN"));
        assertNull(service.get(id).getSn().getProductBatchCode());
        service.station(request(id, "new-unbatched-product", "1", "1"));
        assertEquals(1, service.get(id).getStations().size());
        assertNull(service.get(id).getSn().getProductBatchCode());
        verify(barcodeService).autoGenerateBarcode(anyInt(), eq(501L), eq("NEW-UNBATCHED-SN"), eq("NEW-UNBATCHED-SN"));
    }

    @Test
    void unbatchedWorkOrderRejectsAnExistingSnWithAnActualDifferentBatch() {
        when(workOrderMapper.selectByIdForUpdate(30L)).thenReturn(new MesProWorkOrderDO().setId(30L).setProductId(20L));
        when(snMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(new MesWmSnDO()
                .setId(500L).setCode("BATCHED-SN").setItemId(20L).setBatchCode("OTHER-PRODUCT-BATCH")));
        assertThrows(ServiceException.class, () -> service.register(new MesProSnTraceRegisterReqVO().setWorkOrderId(30L).setSnId(500L)));
    }

    @Test
    void nullBatchRemainsTraceableByActualSourceWithoutInventingBatch() {
        first.setBatchId(null).setBatchCode(null); second.setBatchId(null).setBatchCode(null);
        when(stockMapper.selectById(130L)).thenReturn(stock(130L, null, null));
        when(stockMapper.selectById(131L)).thenReturn(stock(131L, null, null));
        movements = List.of(movement(130L, null, null, "-1"), movement(131L, null, null, "-3"));
        service.station(request(trace.getId(), "unbatched", "1", "1"));
        assertTrue(service.get(trace.getId()).getStations().get(0).getMaterials().stream().allMatch(m -> m.getBatchId() == null));
    }

    @Test
    void concurrentLastUnitRegistrationsSerializeOnFeedbackAndOnlyOneCommits() throws Exception {
        feedback.setFeedbackQuantity(BigDecimal.ONE);
        MesProSnTraceDO contender = trace("SN-CONCURRENT", 2L, 1L);
        when(feedbackMapper.selectByIdForUpdate(10L)).thenAnswer(invocation -> {
            // A real transactional H2 row lock substitutes for the existing feedback mapper.
            jdbc.queryForObject("SELECT id FROM sn_trace_feedback_guard WHERE id=10 FOR UPDATE", Long.class);
            return feedback;
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long id : List.of(trace.getId(), contender.getId())) {
                results.add(executor.submit(() -> {
                    TenantContextHolder.setTenantId(1L);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            new LoginUser().setId(99L), null, List.of()));
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        service.station(request(id, "competing-" + id, "1", "3"));
                        return true;
                    } catch (ServiceException rejected) {
                        assertEquals(SnTraceErrorConstants.FEEDBACK_FULL.getCode(), rejected.getCode());
                        return false;
                    } finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1L, stationMapper.selectCount());
            assertEquals(2L, materialMapper.selectCount());
        } finally { executor.shutdownNow(); }
    }

    private MesProSnTraceDO trace(String code, Long snId, Long tenant) {
        MesProSnTraceDO row = new MesProSnTraceDO().setSnId(snId).setSnCode(code).setWorkOrderId(30L)
                .setProductId(20L).setProductBatchCode("PB").setStatus("REGISTERED");
        row.setTenantId(tenant); traceMapper.insert(row); return row;
    }
    private static MesWmItemConsumeDetailDO detail(Long id, Long stock, Long batch, String code, String qty) {
        return new MesWmItemConsumeDetailDO().setId(id).setConsumeId(100L).setLineId(110L).setMaterialStockId(stock)
                .setItemId(200L).setQuantity(new BigDecimal(qty)).setBatchId(batch).setBatchCode(code).setWarehouseId(150L);
    }
    private static MesWmMaterialStockDO stock(Long id, Long batch, String code) {
        return new MesWmMaterialStockDO().setId(id).setWorkOrderId(30L).setItemId(200L)
                .setBatchId(batch).setBatchCode(code).setWarehouseId(150L);
    }
    private static MesWmTransactionDO movement(Long stock, Long batch, String code, String qty) {
        return new MesWmTransactionDO().setBizLineId(110L).setMaterialStockId(stock).setItemId(200L)
                .setBatchId(batch).setBatchCode(code).setQuantity(new BigDecimal(qty));
    }
    private static MesProSnTraceStationReqVO request(Long traceId, String key, String first, String second) {
        List<MesProSnTraceStationReqVO.Material> materials = new ArrayList<>();
        if (new BigDecimal(first).signum() > 0) materials.add(new MesProSnTraceStationReqVO.Material().setConsumeDetailId(120L).setQuantity(new BigDecimal(first)));
        if (new BigDecimal(second).signum() > 0) materials.add(new MesProSnTraceStationReqVO.Material().setConsumeDetailId(121L).setQuantity(new BigDecimal(second)));
        return new MesProSnTraceStationReqVO().setRequestKey(key).setTraceId(traceId).setFeedbackId(10L).setMaterials(materials);
    }
}
