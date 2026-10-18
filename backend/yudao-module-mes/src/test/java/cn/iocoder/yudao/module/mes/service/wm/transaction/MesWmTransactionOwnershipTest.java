package cn.iocoder.yudao.module.mes.service.wm.transaction;

import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.transaction.MesWmTransactionMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH;

@ExtendWith(MockitoExtension.class)
class MesWmTransactionOwnershipTest {
    @InjectMocks private MesWmTransactionServiceImpl service;
    @Mock private MesWmTransactionMapper transactionMapper;
    @Mock private MesWmMaterialStockService materialStockService;
    @Mock private MesWmBatchService batchService;
    @Mock private MesMdItemService itemService;
    @Mock private MesWmWarehouseService warehouseService;
    @Mock private MesWmWarehouseLocationService locationService;
    @Mock private MesWmWarehouseAreaService areaService;
    @Mock private cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService kittingService;

    @BeforeEach
    void setup() {
        lenient().when(warehouseService.validateWarehouseExists(2L))
                .thenReturn(new MesWmWarehouseDO().setId(2L).setCode("PHYSICAL").setFrozen(false));
        lenient().when(locationService.validateWarehouseLocationExists(3L))
                .thenReturn(new MesWmWarehouseLocationDO().setId(3L).setFrozen(false));
        lenient().when(areaService.validateWarehouseAreaExists(4L))
                .thenReturn(new MesWmWarehouseAreaDO().setId(4L).setFrozen(false));
        lenient().when(itemService.validateItemExists(1L)).thenReturn(new MesMdItemDO().setId(1L).setBatchFlag(false));
    }

    private MesWmTransactionSaveReqDTO request(int type) {
        return new MesWmTransactionSaveReqDTO().setType(type).setItemId(1L).setWarehouseId(2L)
                .setLocationId(3L).setAreaId(4L).setBizType(1).setBizId(1L).setBizCode("TEST").setBizLineId(1L)
                .setQuantity(type == 1 ? BigDecimal.TEN : BigDecimal.TEN.negate());
    }

    @Test
    void outboundDeductsSelectedRecordAndNeverAllowsNegativeStock() {
        var stock = MesWmMaterialStockDO.builder().id(91L).quantity(BigDecimal.TEN).frozen(false).build();
        when(materialStockService.validateSelectedStock(91L, 1L, null, null, 2L, 3L, 4L, BigDecimal.TEN)).thenReturn(stock);
        service.createTransaction(request(2).setMaterialStockId(91L).setCheckFlag(false));
        verify(materialStockService).updateMaterialStockQuantity(91L, BigDecimal.TEN.negate(), true);
        verify(materialStockService, never()).getOrCreateMaterialStock(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unassignedWipIsRejectedBeforeAnyStockChange() {
        when(warehouseService.validateWarehouseExists(2L))
                .thenReturn(new MesWmWarehouseDO().setId(2L).setCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE));
        assertServiceException(() -> service.createTransaction(request(2).setMaterialStockId(91L)),
                WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        verify(materialStockService, never()).updateMaterialStockQuantity(any(), any(), anyBoolean());
    }

    @Test
    void inboundWipUsesWorkOrderAndVendorAsIndependentStockDimensions() {
        when(warehouseService.validateWarehouseExists(2L))
                .thenReturn(new MesWmWarehouseDO().setId(2L).setCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE));
        var stock = MesWmMaterialStockDO.builder().id(92L).workOrderId(101L).vendorId(8L).frozen(false).build();
        when(materialStockService.getOrCreateMaterialStock(1L, 2L, 3L, 4L, null, null, 8L, null, 101L)).thenReturn(stock);
        service.createTransaction(request(1).setWorkOrderId(101L).setVendorId(8L));
        var saved = ArgumentCaptor.forClass(MesWmTransactionDO.class);
        verify(transactionMapper).insert(saved.capture());
        assertEquals(101L, saved.getValue().getWorkOrderId());
        assertEquals(92L, saved.getValue().getMaterialStockId());
    }

    @Test
    void transferRetainsVendorFromTheActualSourceStock() {
        when(transactionMapper.selectById(71L)).thenReturn(MesWmTransactionDO.builder()
                .id(71L).materialStockId(90L).itemId(1L).quantity(BigDecimal.TEN.negate()).build());
        when(materialStockService.getMaterialStock(90L)).thenReturn(MesWmMaterialStockDO.builder().id(90L).vendorId(8L).build());
        when(materialStockService.getOrCreateMaterialStock(1L, 2L, 3L, 4L, null, null, 8L, null, null))
                .thenReturn(MesWmMaterialStockDO.builder().id(92L).frozen(false).build());
        service.createTransaction(request(1).setRelatedTransactionId(71L));
        verify(materialStockService).getOrCreateMaterialStock(1L, 2L, 3L, 4L, null, null, 8L, null, null);
    }
}
