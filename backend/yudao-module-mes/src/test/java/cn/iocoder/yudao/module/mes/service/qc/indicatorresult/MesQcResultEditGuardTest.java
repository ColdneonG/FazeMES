package cn.iocoder.yudao.module.mes.service.qc.indicatorresult;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.ipqc.MesQcIpqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.iqc.MesQcIqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.ipqc.MesQcIpqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesQcResultEditGuardTest {
    @InjectMocks private MesQcResultEditGuard guard;
    @Mock private MesQcIqcMapper iqcMapper;
    @Mock private MesQcIpqcMapper ipqcMapper;
    @Mock private MesQcOqcMapper oqcMapper;
    @Mock private MesQcRqcMapper rqcMapper;

    @Test void completedInspectionsOfEveryTypeRejectResultMutation() {
        when(iqcMapper.selectOne(any(Wrapper.class))).thenReturn(new MesQcIqcDO().setStatus(4));
        when(ipqcMapper.selectOne(any(Wrapper.class))).thenReturn(new MesQcIpqcDO().setStatus(4));
        when(oqcMapper.selectOne(any(Wrapper.class))).thenReturn(new MesQcOqcDO().setStatus(4));
        when(rqcMapper.selectOne(any(Wrapper.class))).thenReturn(new MesQcRqcDO().setStatus(4));
        for (MesQcTypeEnum type : MesQcTypeEnum.values()) {
            assertThrows(ServiceException.class, () -> guard.requireDraft(1L, type.getType()));
        }
    }
    @Test void draftOqcCanBeEditedWithHeaderLock() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MesQcOqcDO.class);
        when(oqcMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"));
            return new MesQcOqcDO().setStatus(0);
        });
        assertDoesNotThrow(() -> guard.requireDraft(1L, MesQcTypeEnum.OQC.getType()));
    }
    @Test void missingOrUnknownInspectionFailsClosed() {
        assertThrows(ServiceException.class, () -> guard.requireDraft(1L, MesQcTypeEnum.OQC.getType()));
        assertThrows(ServiceException.class, () -> guard.requireDraft(1L, 999));
    }
}
