package cn.iocoder.yudao.module.mes.service.qc.analysis;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.qc.analysis.vo.MesQcComparisonReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.analysis.MesQcPeriodCount;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.analysis.MesQcAnalysisMapper;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesQcAnalysisServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private MesQcAnalysisService service;
    @Mock private MesQcAnalysisMapper mapper;
    private MesQcComparisonReqVO request() {
        return new MesQcComparisonReqVO().setStartDate(LocalDate.of(2026,10,1)).setEndDate(LocalDate.of(2026,10,17));
    }
    private MesQcPeriodCount count(long total,long failed) { return new MesQcPeriodCount().setStage("IQC").setTotal(total).setFailed(failed); }
    @Test void comparisonFlagsBothHighRateAndDeterioration() {
        when(mapper.selectPeriod(any(),any(),any(),any())).thenReturn(List.of(count(10,3)),List.of(count(10,1)));
        var result=service.compare(request()); var row=result.getRows().get(0);
        assertEquals(0,new BigDecimal("30").compareTo(row.getCurrentRate()));
        assertEquals(0,new BigDecimal("20").compareTo(row.getChangePoints())); assertTrue(row.isAlert());
        assertEquals(LocalDate.of(2025,10,1),result.getComparisonStartDate());
        verify(mapper).selectPeriod(eq(LocalDate.of(2026,10,1).atStartOfDay()),eq(LocalDate.of(2026,10,18).atStartOfDay()),any(),any());
    }
    @Test void previousPeriodIsAdjacentAndSameLength() {
        when(mapper.selectPeriod(any(),any(),any(),any())).thenReturn(List.of());
        var result=service.compare(request().setCompareMode("PREVIOUS"));
        assertEquals(LocalDate.of(2026,9,14),result.getComparisonStartDate());
        assertEquals(LocalDate.of(2026,9,30),result.getComparisonEndDate());
    }
    @Test void noBaselineIsNotMisrepresentedAsZeroRate() {
        when(mapper.selectPeriod(any(),any(),any(),any())).thenReturn(List.of(count(10,0)),List.of());
        var row=service.compare(request()).getRows().get(0);
        assertNull(row.getComparisonRate()); assertNull(row.getChangePoints()); assertFalse(row.isAlert());
    }
    @Test void noCurrentDataDoesNotAlertEvenWhenBaselineExists() {
        when(mapper.selectPeriod(any(),any(),any(),any())).thenReturn(List.of(),List.of(count(10,5)));
        var row=service.compare(request()).getRows().get(0);
        assertNull(row.getCurrentRate()); assertFalse(row.isAlert());
    }
    @Test void invalidDateAndThresholdAreRejectedBeforeQueries() {
        assertThrows(ServiceException.class,()->service.compare(request().setEndDate(LocalDate.of(2026,9,1))));
        assertThrows(ServiceException.class,()->service.compare(request().setEndDate(LocalDate.of(2028,1,1))));
        assertThrows(IllegalArgumentException.class,()->service.compare(request().setRateThreshold(BigDecimal.valueOf(101))));
        verifyNoInteractions(mapper);
    }
}
