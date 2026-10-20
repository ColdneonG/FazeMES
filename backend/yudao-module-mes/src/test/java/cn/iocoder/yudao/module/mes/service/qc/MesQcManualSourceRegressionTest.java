package cn.iocoder.yudao.module.mes.service.qc;

import cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.ipqc.MesQcIpqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.iqc.MesQcIqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.ipqc.MesQcIpqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.mes.service.qc.iqc.MesQcIqcServiceImpl;
import cn.iocoder.yudao.module.mes.service.qc.ipqc.MesQcIpqcServiceImpl;
import cn.iocoder.yudao.module.mes.service.qc.oqc.MesQcOqcServiceImpl;
import cn.iocoder.yudao.module.mes.service.qc.rqc.MesQcRqcServiceImpl;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Reproduces the production MySQL DEFAULT 0 source type, absent from the H2 null-source fixtures. */
@ExtendWith(MockitoExtension.class)
class MesQcManualSourceRegressionTest {
    @InjectMocks private MesQcIqcServiceImpl iqcService;
    @InjectMocks private MesQcIpqcServiceImpl ipqcService;
    @InjectMocks private MesQcOqcServiceImpl oqcService;
    @InjectMocks private MesQcRqcServiceImpl rqcService;
    @Mock private MesQcIqcMapper iqcMapper;
    @Mock private MesQcIpqcMapper ipqcMapper;
    @Mock private MesQcOqcMapper oqcMapper;
    @Mock private MesQcRqcMapper rqcMapper;
    @Mock private MesQcIndicatorResultService indicatorResultService;

    @ParameterizedTest
    @ValueSource(strings = {"IQC", "IPQC", "OQC", "RQC"})
    void defaultZeroWithNoSourceIdsFinishesManualInspection(String stage) {
        prepare(stage, null);
        assertDoesNotThrow(() -> finish(stage));
        switch (stage) {
            case "IQC" -> verify(iqcMapper).updateById(argThat((MesQcIqcDO row) -> row.getStatus() == 4));
            case "IPQC" -> verify(ipqcMapper).updateById(argThat((MesQcIpqcDO row) -> row.getStatus() == 4));
            case "OQC" -> verify(oqcMapper).updateById(argThat((MesQcOqcDO row) -> row.getStatus() == 4));
            case "RQC" -> verify(rqcMapper).updateById(argThat((MesQcRqcDO row) -> row.getStatus() == 4));
            default -> fail(stage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"IQC", "IPQC", "OQC", "RQC"})
    void zeroTypeWithSourceIdsRemainsInvalid(String stage) {
        prepare(stage, 42L);
        if(stage.equals("OQC")||stage.equals("RQC"))assertThrows(ServiceException.class,()->finish(stage));
        else assertThrows(IllegalArgumentException.class, () -> finish(stage));
    }

    private void prepare(String stage, Long sourceId) {
        switch (stage) {
            case "IQC" -> when(iqcMapper.selectById(1L)).thenReturn(new MesQcIqcDO()
                    .setId(1L).setStatus(0).setCheckResult(1).setSourceDocType(0).setSourceDocId(sourceId));
            case "IPQC" -> when(ipqcMapper.selectById(1L)).thenReturn(new MesQcIpqcDO()
                    .setId(1L).setStatus(0).setCheckResult(1).setSourceDocType(0).setSourceDocId(sourceId));
            case "OQC" -> {var qc=new MesQcOqcDO().setId(1L).setStatus(0).setCheckResult(1).setSourceDocType(0).setSourceDocId(sourceId);when(oqcMapper.selectById(1L)).thenReturn(qc);if(sourceId==null)when(oqcMapper.selectOne(org.mockito.ArgumentMatchers.any(Wrapper.class))).thenReturn(qc);}
            case "RQC" -> {var qc=new MesQcRqcDO().setId(1L).setStatus(0).setCheckResult(1).setSourceDocType(0).setSourceDocId(sourceId);when(rqcMapper.selectById(1L)).thenReturn(qc);if(sourceId==null)when(rqcMapper.selectOne(org.mockito.ArgumentMatchers.any(Wrapper.class))).thenReturn(qc);}
            default -> fail(stage);
        }
    }

    private void finish(String stage) {
        switch (stage) {
            case "IQC" -> iqcService.finishIqc(1L);
            case "IPQC" -> ipqcService.finishIpqc(1L);
            case "OQC" -> oqcService.finishOqc(1L);
            case "RQC" -> rqcService.finishRqc(1L);
            default -> fail(stage);
        }
    }
}
