package cn.iocoder.yudao.module.mes.service.wm.transaction;

import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesWmTransactionAreaLockTest {
    @Spy @InjectMocks private MesWmTransactionServiceImpl service;
    @Mock private MesWmMaterialStockService materialStockService;
    @Mock private cn.iocoder.yudao.module.mes.service.cost.MesCostService costService;
    @Mock private cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService returnSourceService;

    @Test
    void batchPrelocksDistinctAreasInOrderBeforeAnyMovement() {
        var first = new MesWmTransactionSaveReqDTO().setAreaId(90L);
        var second = new MesWmTransactionSaveReqDTO().setAreaId(20L);
        var third = new MesWmTransactionSaveReqDTO().setAreaId(90L);
        doReturn(1L).when(service).createTransaction(any());
        service.createTransactionList(List.of(first, second, third));
        var locks = inOrder(returnSourceService, materialStockService, service);
        locks.verify(returnSourceService).preflight(List.of(first, second, third));
        locks.verify(materialStockService).lockStockArea(20L);
        locks.verify(materialStockService).lockStockArea(90L);
        locks.verify(service).createTransaction(first);
        locks.verify(service).createTransaction(second);
        locks.verify(service).createTransaction(third);
        verify(materialStockService, times(1)).lockStockArea(90L);
    }

    @Test
    void failedAreaLockPreventsEveryStockMovement() {
        doThrow(new IllegalStateException("area removed")).when(materialStockService).lockStockArea(20L);
        assertThrows(IllegalStateException.class, () -> service.createTransactionList(List.of(
                new MesWmTransactionSaveReqDTO().setAreaId(90L), new MesWmTransactionSaveReqDTO().setAreaId(20L))));
        verify(service, never()).createTransaction(any());
    }
}
