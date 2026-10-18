package cn.iocoder.yudao.module.mes.service.md.unitmeasure;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.md.unitmeasure.vo.MesMdUnitMeasureSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.unitmeasure.MesMdUnitMeasureDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.unitmeasure.MesMdUnitMeasureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMdUnitMeasureValidationTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdUnitMeasureServiceImpl service;
    @Mock private MesMdUnitMeasureMapper mapper;

    private MesMdUnitMeasureSaveReqVO secondary() {
        return new MesMdUnitMeasureSaveReqVO().setCode("G").setName("克").setStatus(0)
                .setPrimaryFlag(false).setPrimaryId(1L).setChangeRate(new BigDecimal("0.001"));
    }
    private void primary() {
        when(mapper.selectById(1L)).thenReturn(new MesMdUnitMeasureDO().setId(1L).setPrimaryFlag(true).setStatus(0));
    }
    @Test void missingPrimaryIsRejected() {
        assertThrows(ServiceException.class, () -> service.createUnitMeasure(secondary().setPrimaryId(null)));
        verify(mapper, never()).insert(any(MesMdUnitMeasureDO.class));
    }
    @Test void disabledPrimaryIsRejected() {
        when(mapper.selectById(1L)).thenReturn(new MesMdUnitMeasureDO().setPrimaryFlag(true).setStatus(1));
        assertThrows(ServiceException.class, () -> service.createUnitMeasure(secondary()));
    }
    @Test void secondaryCannotBeUsedAsPrimary() {
        when(mapper.selectById(1L)).thenReturn(new MesMdUnitMeasureDO().setPrimaryFlag(false).setStatus(0));
        assertThrows(ServiceException.class, () -> service.createUnitMeasure(secondary()));
    }
    @Test void positiveRateIsRequired() {
        primary();
        for (BigDecimal value : new BigDecimal[]{null, BigDecimal.ZERO, BigDecimal.ONE.negate(), new BigDecimal("0.0000001")}) {
            assertThrows(ServiceException.class, () -> service.createUnitMeasure(secondary().setChangeRate(value)));
        }
        verify(mapper, never()).insert(any(MesMdUnitMeasureDO.class));
    }
    @Test void validConversionIsPersisted() {
        primary(); service.createUnitMeasure(secondary());
        ArgumentCaptor<MesMdUnitMeasureDO> record = ArgumentCaptor.forClass(MesMdUnitMeasureDO.class);
        verify(mapper).insert(record.capture());
        assertEquals(1L, record.getValue().getPrimaryId());
        assertEquals(0, new BigDecimal("0.001").compareTo(record.getValue().getChangeRate()));
    }
    @Test void primaryWithDependantsCannotBeDemotedOrDisabled() {
        when(mapper.selectById(2L)).thenReturn(new MesMdUnitMeasureDO().setId(2L));
        when(mapper.selectCountByPrimaryId(2L)).thenReturn(1L);
        assertThrows(ServiceException.class, () -> service.updateUnitMeasure(secondary().setId(2L)));
        assertThrows(ServiceException.class, () -> service.updateUnitMeasure(secondary().setId(2L).setPrimaryFlag(true).setStatus(1)));
        verify(mapper, never()).updateById(any(MesMdUnitMeasureDO.class));
    }
    @Test void primaryClearsStaleConversionFields() {
        MesMdUnitMeasureSaveReqVO req = secondary().setPrimaryFlag(true);
        service.createUnitMeasure(req);
        assertNull(req.getPrimaryId()); assertNull(req.getChangeRate());
    }
}
