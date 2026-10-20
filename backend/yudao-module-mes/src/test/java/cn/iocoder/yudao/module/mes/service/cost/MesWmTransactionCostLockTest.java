package cn.iocoder.yudao.module.mes.service.cost;

import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionServiceImpl;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lock ordering is a concurrency invariant, independent of the database implementation. */
class MesWmTransactionCostLockTest {
    @Test void individualProductionEventLocksOrderBeforeArea() {
        MesWmTransactionServiceImpl service=new MesWmTransactionServiceImpl();
        MesCostService costs=mock(MesCostService.class);MesWmWarehouseAreaService areas=mock(MesWmWarehouseAreaService.class);
        ReflectionTestUtils.setField(service,"costService",costs);ReflectionTestUtils.setField(service,"areaService",areas);
        ReflectionTestUtils.setField(service,"returnSourceService",mock(MesWmReturnSalesSourceService.class));
        doThrow(new IllegalStateException("stop before inventory mutation")).when(areas).validateWarehouseAreaExists(1L,2L,3L);
        MesWmTransactionSaveReqDTO req=req(10L,3L).setWarehouseId(1L).setLocationId(2L);
        assertThrows(IllegalStateException.class,()->service.createTransaction(req));
        var order=inOrder(costs,areas);order.verify(costs).assertWorkOrderMutable(10L);order.verify(areas).validateWarehouseAreaExists(1L,2L,3L);
    }
    @Test void batchLocksDistinctOrdersSortedBeforeSortedAreas() {
        MesWmTransactionServiceImpl service=new MesWmTransactionServiceImpl();MesCostService costs=mock(MesCostService.class);MesWmMaterialStockService stocks=mock(MesWmMaterialStockService.class);
        ReflectionTestUtils.setField(service,"costService",costs);ReflectionTestUtils.setField(service,"materialStockService",stocks);
        ReflectionTestUtils.setField(service,"returnSourceService",mock(MesWmReturnSalesSourceService.class));
        doThrow(new IllegalStateException("stop before inventory mutation")).when(stocks).lockStockArea(8L);
        assertThrows(IllegalStateException.class,()->service.createTransactionList(List.of(req(3L,9L),req(1L,8L),req(3L,9L))));
        var order=inOrder(costs,stocks);order.verify(costs).assertWorkOrderMutable(1L);order.verify(costs).assertWorkOrderMutable(3L);order.verify(stocks).lockStockArea(8L);verifyNoMoreInteractions(costs);
    }
    private MesWmTransactionSaveReqDTO req(Long workorder,Long area) {
        return new MesWmTransactionSaveReqDTO().setType(2).setQuantity(BigDecimal.ONE.negate()).setBizType(MesBizTypeConstants.WM_ITEM_CONSUME).setWorkOrderId(workorder).setAreaId(area);
    }
}
