package cn.iocoder.yudao.module.mes.service.wm.productproduce;

import cn.hutool.core.collection.ListUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseAreaDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseLocationDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productproduce.MesWmProductProduceMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productproduce.MesWmProductProduceLineMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productproduce.MesWmProductProduceDetailMapper;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductProduceStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.service.pro.workorder.MesProWorkOrderService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseLocationService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MesWmProductProduceServiceImpl} 的单元测试
 *
 * @author 芋道源码
 */
@Import(MesWmProductProduceServiceImpl.class)
public class MesWmProductProduceServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MesWmProductProduceServiceImpl productProduceService;

    @Resource
    private MesWmProductProduceMapper productProduceMapper;
    @Resource
    private MesWmProductProduceLineMapper produceLineMapper;
    @Resource
    private MesWmProductProduceDetailMapper produceDetailMapper;

    @MockitoBean
    private MesWmProductProduceLineService productProduceLineService;
    @MockitoBean
    private MesWmProductProduceDetailService productProduceDetailService;
    @MockitoBean
    private MesProWorkOrderService workOrderService;
    @MockitoBean
    private MesWmBatchService batchService;
    @MockitoBean
    private MesWmTransactionService wmTransactionService;
    @MockitoBean
    private MesWmWarehouseService warehouseService;
    @MockitoBean
    private MesWmWarehouseLocationService locationService;
    @MockitoBean
    private MesWmWarehouseAreaService areaService;

    @Test
    public void testSplitPendingAndFinishProduce_withUnqualified() {
        // 准备数据：插入一个 PREPARE 状态的产出单
        Long feedbackId = randomLongId();
        Long sourceLineId = 100L;
        Long itemId = randomLongId();
        Long batchId = randomLongId();
        String batchCode = "BATCH-001";

        MesWmProductProduceDO produce = randomPojo(MesWmProductProduceDO.class, o -> {
            o.setFeedbackId(feedbackId);
            o.setStatus(MesWmProductProduceStatusEnum.PREPARE.getStatus());
        });
        productProduceMapper.insert(produce);

        // mock: 虚拟线边库
        Long whId = randomLongId(), locId = randomLongId(), areaId = randomLongId();
        MesWmWarehouseDO wh = new MesWmWarehouseDO(); wh.setId(whId);
        MesWmWarehouseLocationDO loc = new MesWmWarehouseLocationDO(); loc.setId(locId);
        MesWmWarehouseAreaDO area = new MesWmWarehouseAreaDO(); area.setId(areaId);
        when(warehouseService.getWarehouseByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE)).thenReturn(wh);
        when(locationService.getWarehouseLocationByCode(MesWmWarehouseLocationDO.WIP_VIRTUAL_LOCATION)).thenReturn(loc);
        when(areaService.getWarehouseAreaByCode(MesWmWarehouseAreaDO.WIP_VIRTUAL_AREA)).thenReturn(area);

        // mock: 通过 sourceLineId 直接定位 PENDING 产出行
        MesWmProductProduceLineDO pendingLine = MesWmProductProduceLineDO.builder()
                .id(sourceLineId).produceId(produce.getId()).feedbackId(feedbackId)
                .itemId(itemId).quantity(BigDecimal.valueOf(100))
                .batchId(batchId).batchCode(batchCode)
                .qualityStatus(MesWmQualityStatusEnum.PENDING.getStatus())
                .build();
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(sourceLineId))
                .thenReturn(pendingLine);

        // mock: finishProductProduce 内部查行和明细（用于创建库存事务）
        MesWmProductProduceLineDO qualifiedLine = MesWmProductProduceLineDO.builder()
                .id(100L).produceId(produce.getId()).itemId(itemId)
                .batchId(batchId).batchCode(batchCode)
                .quantity(BigDecimal.valueOf(80))
                .qualityStatus(MesWmQualityStatusEnum.PASS.getStatus())
                .build();
        MesWmProductProduceLineDO unqualifiedLine = MesWmProductProduceLineDO.builder()
                .id(101L).produceId(produce.getId()).itemId(itemId)
                .batchId(batchId).batchCode(batchCode)
                .quantity(BigDecimal.valueOf(20))
                .qualityStatus(MesWmQualityStatusEnum.FAIL.getStatus())
                .build();
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId()))
                .thenReturn(ListUtil.of(qualifiedLine, unqualifiedLine));

        // mock: finishProductProduce 中按行查明细
        MesWmProductProduceDetailDO qualifiedDetail = MesWmProductProduceDetailDO.builder()
                .produceId(produce.getId()).lineId(100L).itemId(itemId).batchId(batchId).batchCode(batchCode)
                .quantity(BigDecimal.valueOf(80)).build();
        MesWmProductProduceDetailDO unqualifiedDetail = MesWmProductProduceDetailDO.builder()
                .produceId(produce.getId()).lineId(101L).itemId(itemId).batchId(batchId).batchCode(batchCode)
                .quantity(BigDecimal.valueOf(20)).build();
        when(productProduceDetailService.getProductProduceDetailListByLineId(100L))
                .thenReturn(ListUtil.of(qualifiedDetail));
        when(productProduceDetailService.getProductProduceDetailListByLineId(101L))
                .thenReturn(ListUtil.of(unqualifiedDetail));

        // 调用
        productProduceService.splitPendingAndFinishProduce(feedbackId, sourceLineId,
                BigDecimal.valueOf(80), BigDecimal.valueOf(20));

        // 断言 1：不合格品行 - 新建了一行
        ArgumentCaptor<MesWmProductProduceLineDO> lineCaptor = ArgumentCaptor.forClass(MesWmProductProduceLineDO.class);
        verify(productProduceLineService).createProductProduceLine(lineCaptor.capture());
        MesWmProductProduceLineDO createdLine = lineCaptor.getValue();
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), createdLine.getQualityStatus());
        assertEquals(0, BigDecimal.valueOf(20).compareTo(createdLine.getQuantity()));
        assertEquals(itemId, createdLine.getItemId());

        // 断言 2：原待检行 - 更新为合格品
        ArgumentCaptor<MesWmProductProduceLineDO> updateCaptor = ArgumentCaptor.forClass(MesWmProductProduceLineDO.class);
        verify(productProduceLineService).updateProductProduceLine(updateCaptor.capture());
        MesWmProductProduceLineDO updatedLine = updateCaptor.getValue();
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), updatedLine.getQualityStatus());
        assertEquals(0, BigDecimal.valueOf(80).compareTo(updatedLine.getQuantity()));

        // 断言 3：生成了 2 条明细（不合格品明细 + 合格品明细）
        verify(productProduceDetailService, times(2)).createProductProduceDetail(any());

        // 断言 4：产出单状态更新为已完成
        MesWmProductProduceDO updatedProduce = productProduceMapper.selectById(produce.getId());
        assertEquals(MesWmProductProduceStatusEnum.FINISHED.getStatus(), updatedProduce.getStatus());

        // 断言 5：创建了库存事务
        verify(wmTransactionService, times(2)).createTransaction(any());
    }

    @Test
    public void testSplitPendingAndFinishProduce_allQualified() {
        // 准备数据：全部合格品场景
        Long feedbackId = randomLongId();
        Long sourceLineId = 200L;
        Long itemId = randomLongId();

        MesWmProductProduceDO produce = randomPojo(MesWmProductProduceDO.class, o -> {
            o.setFeedbackId(feedbackId);
            o.setStatus(MesWmProductProduceStatusEnum.PREPARE.getStatus());
        });
        productProduceMapper.insert(produce);

        // mock: 虚拟线边库
        Long whId = randomLongId(), locId = randomLongId(), areaId = randomLongId();
        MesWmWarehouseDO wh = new MesWmWarehouseDO(); wh.setId(whId);
        MesWmWarehouseLocationDO loc = new MesWmWarehouseLocationDO(); loc.setId(locId);
        MesWmWarehouseAreaDO area = new MesWmWarehouseAreaDO(); area.setId(areaId);
        when(warehouseService.getWarehouseByCode(any())).thenReturn(wh);
        when(locationService.getWarehouseLocationByCode(any())).thenReturn(loc);
        when(areaService.getWarehouseAreaByCode(any())).thenReturn(area);

        // mock: 通过 sourceLineId 直接定位 PENDING 产出行
        MesWmProductProduceLineDO pendingLine = MesWmProductProduceLineDO.builder()
                .id(sourceLineId).produceId(produce.getId()).feedbackId(feedbackId)
                .itemId(itemId).quantity(BigDecimal.valueOf(50))
                .qualityStatus(MesWmQualityStatusEnum.PENDING.getStatus())
                .build();
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(sourceLineId))
                .thenReturn(pendingLine);

        // mock: finishProductProduce 查行和明细
        MesWmProductProduceLineDO qualifiedLine = MesWmProductProduceLineDO.builder()
                .id(200L).produceId(produce.getId()).itemId(itemId)
                .quantity(BigDecimal.valueOf(50))
                .qualityStatus(MesWmQualityStatusEnum.PASS.getStatus())
                .build();
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId()))
                .thenReturn(ListUtil.of(qualifiedLine));
        MesWmProductProduceDetailDO detail = MesWmProductProduceDetailDO.builder()
                .produceId(produce.getId()).lineId(200L).itemId(itemId).quantity(BigDecimal.valueOf(50)).build();
        when(productProduceDetailService.getProductProduceDetailListByLineId(200L))
                .thenReturn(ListUtil.of(detail));

        // 调用：不合格品数量 = 0
        productProduceService.splitPendingAndFinishProduce(feedbackId, sourceLineId,
                BigDecimal.valueOf(50), BigDecimal.ZERO);

        // 断言 1：没有新建行（不合格品数量为 0）
        verify(productProduceLineService, never()).createProductProduceLine(any());

        // 断言 2：更新了原待检行为合格
        ArgumentCaptor<MesWmProductProduceLineDO> updateCaptor = ArgumentCaptor.forClass(MesWmProductProduceLineDO.class);
        verify(productProduceLineService).updateProductProduceLine(updateCaptor.capture());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), updateCaptor.getValue().getQualityStatus());

        // 断言 3：只生成了 1 条明细（全部合格品）
        verify(productProduceDetailService, times(1)).createProductProduceDetail(any());

        // 断言 4：产出单状态更新为已完成
        MesWmProductProduceDO updatedProduce = productProduceMapper.selectById(produce.getId());
        assertEquals(MesWmProductProduceStatusEnum.FINISHED.getStatus(), updatedProduce.getStatus());
    }

    @Test
    public void testSplitPendingAndFinishProduce_produceNotExists() {
        // 调用不存在的 feedbackId，应该抛异常
        Long feedbackId = randomLongId();
        Long sourceLineId = randomLongId();
        ServiceException ex = assertThrows(ServiceException.class, () ->
                productProduceService.splitPendingAndFinishProduce(feedbackId, sourceLineId,
                        BigDecimal.TEN, BigDecimal.ZERO));
        assertEquals(WM_PRODUCT_PRODUCE_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    public void testSplitPendingAndFinishProduce_lineNotExists() {
        // 准备数据：产出单存在，但 sourceLineId 对应的行不存在
        Long feedbackId = randomLongId();
        Long sourceLineId = randomLongId();
        MesWmProductProduceDO produce = randomPojo(MesWmProductProduceDO.class, o -> {
            o.setFeedbackId(feedbackId);
            o.setStatus(MesWmProductProduceStatusEnum.PREPARE.getStatus());
        });
        productProduceMapper.insert(produce);

        // mock: 虚拟线边库
        MesWmWarehouseDO wh = new MesWmWarehouseDO(); wh.setId(1L);
        MesWmWarehouseLocationDO loc = new MesWmWarehouseLocationDO(); loc.setId(1L);
        MesWmWarehouseAreaDO area = new MesWmWarehouseAreaDO(); area.setId(1L);
        when(warehouseService.getWarehouseByCode(any())).thenReturn(wh);
        when(locationService.getWarehouseLocationByCode(any())).thenReturn(loc);
        when(areaService.getWarehouseAreaByCode(any())).thenReturn(area);

        // mock: validateProductProduceLineExists 抛异常
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(sourceLineId))
                .thenThrow(new ServiceException(WM_PRODUCT_PRODUCE_LINE_NOT_EXISTS));

        // 调用，应该抛异常
        ServiceException ex = assertThrows(ServiceException.class, () ->
                productProduceService.splitPendingAndFinishProduce(feedbackId, sourceLineId,
                        BigDecimal.TEN, BigDecimal.ZERO));
        assertEquals(WM_PRODUCT_PRODUCE_LINE_NOT_EXISTS.getCode(), ex.getCode());
    }

    @ParameterizedTest
    @CsvSource({"100,0", "9,0", "-1,11", "11,-1", "0.0000001,9.9999999"})
    void inspectionCannotChangeTheTotalOrUseNegativeQuantities(String good, String bad) {
        MesWmProductProduceDO produce = pendingProduce(71L);
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(700L)).thenReturn(
                MesWmProductProduceLineDO.builder().id(700L).produceId(produce.getId()).feedbackId(71L)
                        .quantity(BigDecimal.TEN).qualityStatus(MesWmQualityStatusEnum.PENDING.getStatus()).build());

        ServiceException ex = assertThrows(ServiceException.class, () -> productProduceService
                .splitPendingAndFinishProduce(71L, 700L, new BigDecimal(good), new BigDecimal(bad)));

        assertEquals(WM_PRODUCT_PRODUCE_DETAIL_QUANTITY_MISMATCH.getCode(), ex.getCode());
        verifyNoInteractions(wmTransactionService, productProduceDetailService, warehouseService);
        verify(productProduceLineService, never()).updateProductProduceLine(any());
        verify(productProduceLineService, never()).createProductProduceLine(any());
    }

    @Test
    void inspectionCannotSplitAnotherProductionDocumentOrFeedback() {
        MesWmProductProduceDO produce = pendingProduce(72L);
        MesWmProductProduceLineDO line = MesWmProductProduceLineDO.builder().id(701L)
                .produceId(produce.getId() + 1).feedbackId(72L).quantity(BigDecimal.TEN)
                .qualityStatus(MesWmQualityStatusEnum.PENDING.getStatus()).build();
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(701L)).thenReturn(line);

        assertThrows(ServiceException.class, () -> productProduceService
                .splitPendingAndFinishProduce(72L, 701L, BigDecimal.TEN, BigDecimal.ZERO));
        line.setProduceId(produce.getId()).setFeedbackId(999L);
        assertThrows(ServiceException.class, () -> productProduceService
                .splitPendingAndFinishProduce(72L, 701L, BigDecimal.TEN, BigDecimal.ZERO));
        verifyNoInteractions(wmTransactionService, productProduceDetailService, warehouseService);
    }

    @Test
    void inspectionCannotSplitAnAlreadyClassifiedLine() {
        MesWmProductProduceDO produce = pendingProduce(73L);
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(702L)).thenReturn(
                MesWmProductProduceLineDO.builder().id(702L).produceId(produce.getId()).feedbackId(73L)
                        .quantity(BigDecimal.TEN).qualityStatus(MesWmQualityStatusEnum.PASS.getStatus()).build());

        assertThrows(ServiceException.class, () -> productProduceService
                .splitPendingAndFinishProduce(73L, 702L, BigDecimal.TEN, BigDecimal.ZERO));
        verifyNoInteractions(wmTransactionService, productProduceDetailService, warehouseService);
    }

    @Test
    void finishedProductionCannotBeExecutedOrSplitAgain() {
        MesWmProductProduceDO produce = pendingProduce(74L);
        productProduceMapper.updateById(new MesWmProductProduceDO().setId(produce.getId())
                .setStatus(MesWmProductProduceStatusEnum.FINISHED.getStatus()));

        assertThrows(ServiceException.class, () -> productProduceService.finishProductProduce(produce.getId()));
        assertThrows(ServiceException.class, () -> productProduceService
                .splitPendingAndFinishProduce(74L, 703L, BigDecimal.TEN, BigDecimal.ZERO));
        verifyNoInteractions(wmTransactionService, productProduceLineService, productProduceDetailService);
    }

    @Test
    void productionCannotBookADetailFromAnotherBatch() {
        MesWmProductProduceDO produce = pendingProduce(75L);
        MesWmProductProduceLineDO line = outputLine(produce.getId(), 710L);
        MesWmProductProduceDetailDO detail = outputDetail(line).setBatchId(999L);
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId())).thenReturn(List.of(line));
        when(productProduceDetailService.getProductProduceDetailListByLineId(line.getId())).thenReturn(List.of(detail));

        assertThrows(ServiceException.class, () -> productProduceService.finishProductProduce(produce.getId()));
        assertEquals(MesWmProductProduceStatusEnum.PREPARE.getStatus(), productProduceMapper.selectById(produce.getId()).getStatus());
        verifyNoInteractions(wmTransactionService);
    }

    @Test
    void laterStockFailureRollsBackEarlierMovementAndDocumentState() {
        MesWmProductProduceDO produce = pendingProduce(76L);
        MesWmProductProduceDO marker = pendingProduce(760L);
        MesWmProductProduceLineDO first = outputLine(produce.getId(), 711L);
        MesWmProductProduceLineDO second = outputLine(produce.getId(), 712L);
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId())).thenReturn(List.of(first, second));
        when(productProduceDetailService.getProductProduceDetailListByLineId(first.getId())).thenReturn(List.of(outputDetail(first)));
        when(productProduceDetailService.getProductProduceDetailListByLineId(second.getId())).thenReturn(List.of(outputDetail(second)));
        doAnswer(invocation -> {
            productProduceMapper.updateById(new MesWmProductProduceDO().setId(marker.getId())
                    .setStatus(MesWmProductProduceStatusEnum.FINISHED.getStatus()));
            return null;
        }).doThrow(new IllegalStateException("second stock movement failed")).when(wmTransactionService).createTransaction(any());

        assertThrows(IllegalStateException.class, () -> productProduceService.finishProductProduce(produce.getId()));

        assertEquals(MesWmProductProduceStatusEnum.PREPARE.getStatus(), productProduceMapper.selectById(marker.getId()).getStatus(),
                "The service transaction must roll back database writes made by an earlier stock movement");
        assertEquals(MesWmProductProduceStatusEnum.PREPARE.getStatus(), productProduceMapper.selectById(produce.getId()).getStatus());
    }

    @Test
    void concurrentFinishBooksStockOnlyOnce() throws Exception {
        MesWmProductProduceDO produce = pendingProduce(77L);
        MesWmProductProduceLineDO line = outputLine(produce.getId(), 713L);
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId())).thenReturn(List.of(line));
        when(productProduceDetailService.getProductProduceDetailListByLineId(line.getId())).thenReturn(List.of(outputDetail(line)));
        CountDownLatch firstMovement = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstMovement.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(wmTransactionService).createTransaction(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> productProduceService.finishProductProduce(produce.getId()));
            assertTrue(firstMovement.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> assertThrows(ServiceException.class,
                    () -> productProduceService.finishProductProduce(produce.getId())));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            verify(wmTransactionService, times(1)).createTransaction(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sixDecimalOutputReachesStockWithoutDatabaseRounding() {
        MesWmProductProduceDO produce = pendingProduce(78L);
        MesWmProductProduceLineDO line = outputLine(produce.getId(), 714L).setQuantity(new BigDecimal("0.000001"));
        produceLineMapper.insert(line);
        produceDetailMapper.insert(outputDetail(line));
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId()))
                .thenAnswer(invocation -> produceLineMapper.selectListByProduceId(produce.getId()));
        when(productProduceDetailService.getProductProduceDetailListByLineId(line.getId()))
                .thenAnswer(invocation -> produceDetailMapper.selectListByLineId(line.getId()));

        productProduceService.finishProductProduce(produce.getId());

        ArgumentCaptor<MesWmTransactionSaveReqDTO> movement = ArgumentCaptor.forClass(MesWmTransactionSaveReqDTO.class);
        verify(wmTransactionService).createTransaction(movement.capture());
        assertEquals(0, new BigDecimal("0.000001").compareTo(movement.getValue().getQuantity()));
        assertEquals(0, new BigDecimal("0.000001").compareTo(produceLineMapper.selectById(line.getId()).getQuantity()));
    }

    @Test
    void fullyRejectedInspectionBooksOnlyRejectedStockAndNoZeroMovement() {
        MesWmProductProduceDO produce = pendingProduce(79L);
        MesWmProductProduceLineDO pending = outputLine(produce.getId(), 715L).setFeedbackId(79L)
                .setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus());
        MesWmProductProduceLineDO rejected = outputLine(produce.getId(), 716L)
                .setQualityStatus(MesWmQualityStatusEnum.FAIL.getStatus());
        when(productProduceLineService.validateProductProduceLineExistsForUpdate(715L)).thenReturn(pending);
        doAnswer(invocation -> {
            invocation.<MesWmProductProduceLineDO>getArgument(0).setId(716L);
            return null;
        }).when(productProduceLineService).createProductProduceLine(any());
        when(productProduceLineService.getProductProduceLineListByProduceId(produce.getId()))
                .thenAnswer(invocation -> List.of(pending, rejected));
        when(productProduceDetailService.getProductProduceDetailListByLineId(715L)).thenReturn(List.of());
        when(productProduceDetailService.getProductProduceDetailListByLineId(716L)).thenReturn(List.of(outputDetail(rejected)));
        when(warehouseService.getWarehouseByCode(any())).thenReturn(new MesWmWarehouseDO().setId(1L));
        when(locationService.getWarehouseLocationByCode(any())).thenReturn(new MesWmWarehouseLocationDO().setId(2L));
        when(areaService.getWarehouseAreaByCode(any())).thenReturn(new MesWmWarehouseAreaDO().setId(3L));

        productProduceService.splitPendingAndFinishProduce(79L, 715L, BigDecimal.ZERO, BigDecimal.TEN);

        assertEquals(0, pending.getQuantity().signum());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), pending.getQualityStatus());
        ArgumentCaptor<MesWmTransactionSaveReqDTO> movement = ArgumentCaptor.forClass(MesWmTransactionSaveReqDTO.class);
        verify(wmTransactionService, times(1)).createTransaction(movement.capture());
        assertEquals(716L, movement.getValue().getBizLineId());
        assertEquals(0, BigDecimal.TEN.compareTo(movement.getValue().getQuantity()));
        assertEquals(MesWmProductProduceStatusEnum.FINISHED.getStatus(), productProduceMapper.selectById(produce.getId()).getStatus());
    }

    private MesWmProductProduceDO pendingProduce(Long feedbackId) {
        MesWmProductProduceDO produce = randomPojo(MesWmProductProduceDO.class, row -> row
                .setFeedbackId(feedbackId).setStatus(MesWmProductProduceStatusEnum.PREPARE.getStatus()));
        productProduceMapper.insert(produce);
        return produce;
    }

    private MesWmProductProduceLineDO outputLine(Long produceId, Long id) {
        return MesWmProductProduceLineDO.builder().id(id).produceId(produceId).itemId(100L)
                .batchId(200L).batchCode("PRODUCT-200").quantity(BigDecimal.TEN)
                .qualityStatus(MesWmQualityStatusEnum.PASS.getStatus()).build();
    }

    private MesWmProductProduceDetailDO outputDetail(MesWmProductProduceLineDO line) {
        return MesWmProductProduceDetailDO.builder().produceId(line.getProduceId()).lineId(line.getId())
                .itemId(line.getItemId()).batchId(line.getBatchId()).batchCode(line.getBatchCode())
                .quantity(line.getQuantity()).build();
    }

}
