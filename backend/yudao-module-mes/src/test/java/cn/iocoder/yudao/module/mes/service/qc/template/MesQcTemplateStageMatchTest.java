package cn.iocoder.yudao.module.mes.service.qc.template;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.*;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.template.MesQcTemplateItemMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesQcTemplateStageMatchTest extends BaseMockitoUnitTest {
    @InjectMocks private MesQcTemplateItemServiceImpl service;
    @Mock private MesQcTemplateItemMapper mapper;
    @Mock private MesQcTemplateService templateService;
    private MesQcTemplateDO template(long id, Integer... stages) {
        return new MesQcTemplateDO().setId(id).setTypes(List.of(MesQcTypeEnum.IPQC.getType())).setStatus(0).setIpqcTypes(List.of(stages));
    }
    private void given(MesQcTemplateDO... templates) {
        when(mapper.selectListByItemId(10L)).thenReturn(List.of(templates).stream().map(t -> new MesQcTemplateItemDO().setItemId(10L).setTemplateId(t.getId())).toList());
        when(templateService.getTemplateList(anyCollection())).thenReturn(List.of(templates));
    }
    @Test void explicitLastInspectionOverridesLegacyGenericTemplate() {
        given(template(1),template(2,6));
        assertEquals(2L,service.getRequiredTemplateByItemIdAndType(10L,MesQcTypeEnum.IPQC.getType(),6).getTemplateId());
        assertEquals(1L,service.getRequiredTemplateByItemIdAndType(10L,MesQcTypeEnum.IPQC.getType(),2).getTemplateId());
    }
    @Test void anotherStageIsNeverUsedAsFallback() {
        given(template(2,2));
        assertThrows(ServiceException.class, () -> service.getRequiredTemplateByItemIdAndType(10L,MesQcTypeEnum.IPQC.getType(),6));
    }
    @Test void overlappingStageTemplatesAreRejectedInsteadOfArbitrarilyPicked() {
        given(template(1,6),template(2,6));
        assertThrows(ServiceException.class, () -> service.getRequiredTemplateByItemIdAndType(10L,MesQcTypeEnum.IPQC.getType(),6));
    }
    @Test void disabledScopedTemplateDoesNotOverrideGeneric() {
        given(template(1),template(2,6).setStatus(1));
        assertEquals(1L,service.getRequiredTemplateByItemIdAndType(10L,MesQcTypeEnum.IPQC.getType(),6).getTemplateId());
    }
}
