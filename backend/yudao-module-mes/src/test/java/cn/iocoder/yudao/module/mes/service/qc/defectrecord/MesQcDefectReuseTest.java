package cn.iocoder.yudao.module.mes.service.qc.defectrecord;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.qc.defectrecord.vo.MesQcDefectRecordSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.defect.MesQcDefectDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.defectrecord.MesQcDefectRecordDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.defectrecord.MesQcDefectRecordMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import cn.iocoder.yudao.module.mes.service.qc.defect.MesQcDefectService;
import cn.iocoder.yudao.module.mes.service.qc.iqc.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesQcDefectReuseTest extends BaseMockitoUnitTest {
    @InjectMocks private MesQcDefectRecordServiceImpl service;
    @Mock private MesQcDefectRecordMapper mapper;
    @Mock private MesQcDefectService defectService;
    @Mock private MesQcIqcService iqcService;
    @Mock private MesQcIqcLineService iqcLineService;
    private MesQcDefectRecordSaveReqVO request() {
        return new MesQcDefectRecordSaveReqVO().setQcId(10L).setLineId(11L).setQcType(MesQcTypeEnum.IQC.getType())
                .setName("手填描述").setLevel(1).setQuantity(2);
    }
    @Test void commonDefectCreatesTrustedNameAndLevelSnapshot() {
        when(defectService.getDefect(7L)).thenReturn(new MesQcDefectDO().setId(7L).setName("划伤").setLevel(3));
        service.createDefectRecord(request().setDefectId(7L));
        ArgumentCaptor<MesQcDefectRecordDO> record = ArgumentCaptor.forClass(MesQcDefectRecordDO.class);
        verify(mapper).insert(record.capture());
        assertEquals("划伤",record.getValue().getName()); assertEquals(3,record.getValue().getLevel());
        assertEquals(7L,record.getValue().getDefectId());
        verify(iqcService).recalculateDefectStats(eq(10L),anyList());
    }
    @Test void missingCommonDefectCannotBeRecorded() {
        assertThrows(ServiceException.class, () -> service.createDefectRecord(request().setDefectId(7L)));
        verify(mapper,never()).insert(any(MesQcDefectRecordDO.class));
    }
    @Test void manualRecordRemainsSupported() {
        service.createDefectRecord(request());
        verifyNoInteractions(defectService);
        verify(mapper).insert(argThat((MesQcDefectRecordDO record) -> record.getDefectId() == null && "手填描述".equals(record.getName())));
    }
    @Test void nonpositiveDefectQuantityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createDefectRecord(request().setQuantity(0)));
        verifyNoInteractions(mapper);
    }
}
