package cn.iocoder.yudao.module.mes.service.wm.productissue;

import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.service.pro.kitting.MesProKittingService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.MesWmTransactionService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesWmProductIssueAreaLockTest {
    @InjectMocks private MesWmProductIssueServiceImpl service;
    @Mock private MesWmProductIssueDetailService issueDetailService;
    @Mock private MesWmWarehouseService warehouseService;
    @Mock private MesWmWarehouseLocationService locationService;
    @Mock private MesWmWarehouseAreaService areaService;
    @Mock private MesProKittingService kittingService;
    @Mock private MesWmTransactionService wmTransactionService;
    private MesWmProductIssueDO issue;

    @BeforeEach
    void fixture() {
        issue = new MesWmProductIssueDO().setId(1L).setWorkOrderId(2L);
        when(warehouseService.getWarehouseByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE)).thenReturn(new MesWmWarehouseDO().setId(3L));
        when(locationService.getWarehouseLocationByCode(MesWmWarehouseLocationDO.WIP_VIRTUAL_LOCATION)).thenReturn(new MesWmWarehouseLocationDO().setId(4L));
        when(areaService.getWarehouseAreaByCode(MesWmWarehouseAreaDO.WIP_VIRTUAL_AREA)).thenReturn(new MesWmWarehouseAreaDO().setId(5L));
        when(issueDetailService.getProductIssueDetailListByIssueId(1L)).thenReturn(List.of(
                new MesWmProductIssueDetailDO().setAreaId(90L).setQuantity(BigDecimal.ONE),
                new MesWmProductIssueDetailDO().setAreaId(20L).setQuantity(BigDecimal.ONE)));
    }

    @Test
    void issuePrelocksAllSourceAndWipAreasBeforeFirstOutbound() {
        ReflectionTestUtils.invokeMethod(service, "createTransactionList", issue);
        var operations = inOrder(kittingService, wmTransactionService);
        operations.verify(kittingService).lockForIssue(2L,List.of(5L,90L,20L));
        operations.verify(wmTransactionService, times(4)).createTransaction(any());
    }

    @Test
    void missingOwnedReservationAreaAbortsBeforeAnyOutboundOrWipInbound() {
        doThrow(new IllegalStateException("reservation area missing")).when(kittingService).lockForIssue(eq(2L),anyList());
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service, "createTransactionList", issue));
        verifyNoInteractions(wmTransactionService);
    }
}
