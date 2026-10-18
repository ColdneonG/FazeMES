package cn.iocoder.yudao.module.mes.service.pro.kitting;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.kitting.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.mes.enums.pro.MesProKittingErrors.*;

@ExtendWith(MockitoExtension.class)
class MesProKittingServiceTest {
    @InjectMocks private MesProKittingServiceImpl service;
    @Mock private MesProKittingMapper mapper;
    @Mock private MesProWorkOrderMapper workOrderMapper;
    @Mock private MesProShortageFollowupMapper followupMapper;
    @Mock private MesProReservationEventMapper eventMapper;
    private final List<MesProMaterialReservationDO> ledger = new ArrayList<>();
    private MesProWorkOrderDO order;
    private KittingStockRow stock;
    private final BigDecimal zero = BigDecimal.ZERO;
    private BigDecimal q(String value) { return new BigDecimal(value); }
    private void eq(String value, BigDecimal actual) { assertEquals(0, q(value).compareTo(actual)); }
    private MesProMaterialReservationDO allocation(Long workOrderId, String amount) {
        var row = new MesProMaterialReservationDO().setId((long) ledger.size()+1).setWorkOrderId(workOrderId)
                .setItemId(20L).setMaterialStockId(30L).setQuantity(q(amount)).setAllocatedQuantity(q(amount))
                .setConsumedQuantity(zero).setReleasedQuantity(zero);
        ledger.add(row); return row;
    }
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L);
        order = new MesProWorkOrderDO().setId(10L).setCode("A").setName("Order A").setStatus(1);
        stock = new KittingStockRow().setId(30L).setItemId(20L).setAreaId(40L).setQuantity(q("10"))
                .setReceiptTime(LocalDateTime.now()).setEligible(true);
        lenient().when(workOrderMapper.selectById(10L)).thenReturn(order);
        lenient().when(workOrderMapper.selectByIdForUpdate(10L)).thenReturn(order);
        lenient().when(mapper.selectRequirements(10L,1L)).thenAnswer(i -> List.of(
                new KittingMaterialVO().setItemId(20L).setRequiredQuantity(q("10"))));
        lenient().when(mapper.selectCandidates(List.of(20L),1L)).thenReturn(List.of(stock));
        lenient().when(mapper.selectStock(30L,1L)).thenReturn(stock);
        lenient().when(mapper.lockStock(30L,1L)).thenReturn(stock);
        lenient().when(mapper.lockForStock(30L,1L)).thenAnswer(i -> new ArrayList<>(ledger));
        lenient().when(mapper.selectForOrder(10L,1L)).thenAnswer(i -> ledger.stream().filter(r -> r.getWorkOrderId()==10L).toList());
        lenient().when(mapper.selectActive(List.of(20L),1L)).thenAnswer(i -> ledger.stream().filter(r -> r.getQuantity().signum()>0).toList());
        lenient().when(mapper.selectNetIssued(10L,20L,1L)).thenReturn(zero);
        lenient().doAnswer(i -> { var row = i.<MesProMaterialReservationDO>getArgument(0); row.setId(100L); ledger.add(row); return 1; })
                .when(mapper).insert(any(MesProMaterialReservationDO.class));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test void expectedDateUsesDatePickerCompatibleIsoEvenWithTimestampDefaults() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var row = new KittingMaterialVO().setExpectedDate(java.time.LocalDate.of(2026,10,18));
        assertEquals("2026-10-18",json.readTree(json.writeValueAsString(row)).get("expectedDate").asText());
    }

    @Test void totalOrderBomIsNotMultipliedAgainAndIssuedMaterialStillCoversDemand() {
        when(mapper.selectNetIssued(10L,20L,1L)).thenReturn(q("4"));
        allocation(11L,"8"); allocation(10L,"1");
        var row = service.analyze(10L).getMaterials().get(0);
        eq("10",row.getRequiredQuantity()); eq("6",row.getOutstandingQuantity());
        eq("1",row.getFreeQuantity()); eq("2",row.getAvailableQuantity()); eq("4",row.getShortageQuantity());
    }
    @Test void frozenStockAndItsOwnReservationCannotMakeTheOrderReady() {
        stock.setEligible(false); allocation(10L,"10");
        var result=service.analyze(10L); assertFalse(result.isReady());
        var row=result.getMaterials().get(0); eq("10",row.getReservedQuantity()); eq("0",row.getAvailableQuantity()); eq("10",row.getShortageQuantity());
    }
    @Test void reserveIsIdempotentAndNeverTakesAnotherOrdersAllocation() {
        allocation(11L,"7");
        service.reserve(10L); service.reserve(10L);
        eq("3",ledger.stream().filter(r->r.getWorkOrderId()==10L).findFirst().orElseThrow().getQuantity());
        eq("10",stock.getQuantity());
        verify(mapper,times(1)).insert(any(MesProMaterialReservationDO.class));
        verify(eventMapper,times(1)).insert(any(MesProReservationEventDO.class));
    }
    @Test void anyPhysicalOutboundMustRespectOtherOrdersReservation() {
        allocation(11L,"8");
        assertServiceException(() -> service.validateAndConsumeOutbound(30L,q("3"),10L,50L),STOCK_RESERVED);
        assertServiceException(() -> service.validateAndConsumeOutbound(30L,q("3"),null,50L),STOCK_RESERVED);
        verify(mapper,never()).updateById(any(MesProMaterialReservationDO.class));
    }
    @Test void productionIssueConsumesOnlyItsOwnAllocationAndKeepsOtherOrder() {
        var own=allocation(10L,"6"); var other=allocation(11L,"4");
        service.validateAndConsumeOutbound(30L,q("5"),10L,50L);
        eq("1",own.getQuantity()); eq("5",own.getConsumedQuantity()); eq("4",other.getQuantity()); eq("10",stock.getQuantity());
    }
    @Test void releaseIsIdempotentAndDoesNotReleaseOtherOrdersStock() {
        var own=allocation(10L,"6"); var other=allocation(11L,"4");
        service.release(10L); service.release(10L);
        eq("0",own.getQuantity()); eq("6",own.getReleasedQuantity()); eq("4",other.getQuantity());
        verify(eventMapper,times(1)).insert(any(MesProReservationEventDO.class));
    }
    @Test void closedOrderAndUnknownBomItemCannotCreateNewReservationsOrFollowups() {
        order.setStatus(3);
        assertServiceException(()->service.reserve(10L),ORDER_NOT_OPEN);
        order.setStatus(1);
        assertServiceException(()->service.saveFollowup(new KittingFollowupReqVO().setWorkOrderId(10L).setItemId(99L).setTrackingStatus("ARRIVED")),INVALID_MATERIAL);
        verifyNoInteractions(followupMapper);
    }
    @Test void missingTenantIsRejectedBeforeAnyCrossTenantRead() {
        TenantContextHolder.clear();
        assertThrows(RuntimeException.class,()->service.analyze(10L));
        verify(mapper,never()).selectRequirements(anyLong(),anyLong());
    }
    @Test void issuePrelocksSourceWipAndAllOwnedAreasInOneSortedSetBeforeStockRows() {
        allocation(10L,"4");
        service.lockForIssue(10L, List.of(90L, 20L, 90L));
        var locks = inOrder(workOrderMapper, mapper);
        locks.verify(workOrderMapper).selectByIdForUpdate(10L);
        locks.verify(mapper).selectForOrder(10L,1L);
        locks.verify(mapper).selectStock(30L,1L);
        locks.verify(mapper).lockArea(20L,1L);
        locks.verify(mapper).lockArea(40L,1L);
        locks.verify(mapper).lockArea(90L,1L);
        verify(mapper,never()).lockStock(anyLong(),anyLong());
        verify(mapper,never()).lockForStock(anyLong(),anyLong());
    }
    @Test void issueCannotSkipAnActiveReservationWhoseStockWasRemoved() {
        allocation(10L,"4");
        when(mapper.selectStock(30L,1L)).thenReturn(null);
        assertServiceException(() -> service.lockForIssue(10L,List.of(90L)),INVALID_RESERVATION);
        verify(mapper,never()).lockArea(anyLong(),anyLong());
    }
}
