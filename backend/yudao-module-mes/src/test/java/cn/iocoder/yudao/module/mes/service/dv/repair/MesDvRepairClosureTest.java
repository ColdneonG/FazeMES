package cn.iocoder.yudao.module.mes.service.dv.repair;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.MesDvRepairSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.machinery.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.repair.*;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.machinery.*;
import cn.iocoder.yudao.module.mes.service.dv.machinery.*;
import cn.iocoder.yudao.module.mes.enums.dv.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class MesDvRepairClosureTest {
    @InjectMocks MesDvRepairServiceImpl service;
    @Mock MesDvRepairMapper repairMapper;
    @Mock MesDvRepairLineMapper repairLineMapper;
    @Mock MesDvRepairLineService repairLineService;
    @Mock MesDvMachineryMapper machineryMapper;
    @Mock MesDvMachineryService machineryService;
    @ParameterizedTest
    @ValueSource(ints = {101, 102})
    void manualCreationCannotReserveAutomaticSource(int sourceType) {
        var request = new MesDvRepairSaveReqVO().setCode("manual").setName("manual")
            .setMachineryId(2L).setSourceDocType(sourceType).setSourceDocId(90L);
        var failure = assertThrows(ServiceException.class, () -> service.createRepair(request));
        assertEquals(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.DV_REPAIR_CHANGED.getCode(), failure.getCode());
        verifyNoInteractions(repairMapper, repairLineMapper, machineryMapper, machineryService);
    }
    @Test void manualCreationPreservesOrdinarySource() {
        var request = new MesDvRepairSaveReqVO().setCode("manual").setName("manual")
            .setMachineryId(2L).setSourceDocType(1).setSourceDocId(90L);
        doAnswer(a->{((MesDvRepairDO)a.getArgument(0)).setId(91L);return 1;}).when(repairMapper).insert(any(MesDvRepairDO.class));
        assertEquals(91L, service.createRepair(request));
        verify(repairMapper).insert(argThat((MesDvRepairDO x)->x.getSourceDocType()==1 && x.getSourceDocId()==90L
            && x.getStatus()==MesDvRepairStatusEnum.PREPARE.getStatus()));
        verify(machineryService).validateMachineryExists(2L);
    }
    void awaiting() {
        var repair=new MesDvRepairDO().setId(1L).setMachineryId(2L).setStatus(MesDvRepairStatusEnum.APPROVING.getStatus());
        when(repairMapper.selectById(1L)).thenReturn(repair);
        when(repairMapper.selectForUpdate(1L)).thenReturn(repair);
        when(machineryMapper.selectForUpdate(2L)).thenReturn(new MesDvMachineryDO().setId(2L));
    }
    @Test void passStopsMachineOnlyWhenAllRepairsClosedAndLocksEquipmentFirst() {
        awaiting();when(repairMapper.selectUnfinishedForUpdate(2L)).thenReturn(List.of());
        service.finishRepair(1L,1,7L);
        verify(machineryMapper).updateById(argThat((MesDvMachineryDO x)->x.getId()==2L && x.getStatus()==MesDvMachineryStatusEnum.STOP.getStatus()));
        var order=inOrder(machineryMapper,repairMapper);
        order.verify(repairMapper).selectById(1L);
        order.verify(machineryMapper).selectForUpdate(2L);
        order.verify(repairMapper).selectForUpdate(1L);
    }
    @Test void failedAcceptanceReturnsToRepairAndKeepsMaintenanceState() {
        awaiting();when(repairMapper.selectUnfinishedForUpdate(2L)).thenReturn(List.of(new MesDvRepairDO().setId(1L)));
        service.finishRepair(1L,2,7L);
        verify(repairMapper).updateById(argThat((MesDvRepairDO x)->x.getResult()==2 && x.getStatus()==MesDvRepairStatusEnum.CONFIRMED.getStatus()));
        verify(machineryMapper).updateById(argThat((MesDvMachineryDO x)->x.getStatus()==MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
    }
    @Test void anotherOpenRepairPreventsRestoringMachine() {
        awaiting();when(repairMapper.selectUnfinishedForUpdate(2L)).thenReturn(List.of(new MesDvRepairDO().setId(3L)));
        service.finishRepair(1L,1,7L);
        verify(machineryMapper).updateById(argThat((MesDvMachineryDO x)->x.getStatus()==MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
    }
    @Test void automaticSourceRetryReusesRepairWithoutExtraLines() {
        when(machineryMapper.selectForUpdate(2L)).thenReturn(new MesDvMachineryDO().setId(2L));
        when(repairMapper.selectSourceForUpdate(101,90L)).thenReturn(new MesDvRepairDO().setId(91L));
        assertEquals(91L,service.createAbnormalRepair(2L,101,90L,List.of(new MesDvRepairLineDO().setMalfunction("leak"))));
        verify(repairMapper,never()).insert(any(MesDvRepairDO.class));
        verifyNoInteractions(repairLineMapper);
    }
    @Test void invalidResultDoesNotChangeEquipment() {
        assertThrows(RuntimeException.class,()->service.finishRepair(1L,9,7L));
        verifyNoInteractions(repairMapper,machineryMapper);
    }
    @Test void firstAbnormalSourceCreatesLinkedRepairAndFaults() {
        when(machineryMapper.selectForUpdate(2L)).thenReturn(new MesDvMachineryDO().setId(2L));
        doAnswer(a->{((MesDvRepairDO)a.getArgument(0)).setId(91L);return 1;}).when(repairMapper).insert(any(MesDvRepairDO.class));
        assertEquals(91L,service.createAbnormalRepair(2L,102,90L,List.of(new MesDvRepairLineDO().setSubjectId(3L).setMalfunction("leak"))));
        verify(repairMapper).insert(argThat((MesDvRepairDO x)->x.getSourceDocType()==102 && x.getSourceDocId()==90L && x.getMachineryId()==2L));
        verify(repairLineMapper).insert(argThat((MesDvRepairLineDO x)->x.getRepairId()==91L && x.getMalfunction().equals("leak")));
        verify(machineryMapper).updateById(argThat((MesDvMachineryDO x)->x.getStatus()==MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
    }
}
