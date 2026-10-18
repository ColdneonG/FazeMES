package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.batchconfig.MesMdItemBatchConfigRespVO;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.batchconfig.MesMdItemBatchConfigSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemBatchConfigDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdItemBatchConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Protects the work-order batch dimension across the actual HTTP and persistence models. */
@ExtendWith(MockitoExtension.class)
class MesMdItemBatchConfigContractTest {
    @InjectMocks private MesMdItemBatchConfigServiceImpl service;
    @Mock private MesMdItemBatchConfigMapper mapper;
    @Mock private MesMdItemService itemService;

    @Test void canonicalAndLegacyRequestNamesPersistTheWorkOrderDimension() {
        when(itemService.getItem(1L)).thenReturn(new MesMdItemDO().setId(1L));
        when(mapper.selectByItemId(1L)).thenReturn(new MesMdItemBatchConfigDO().setId(9L));
        for (String name : new String[]{"workOrderFlag", "workorderFlag"}) {
            MesMdItemBatchConfigSaveReqVO request = JsonUtils.parseObject("{\"itemId\":1,\"" + name + "\":true}", MesMdItemBatchConfigSaveReqVO.class);
            service.saveItemBatchConfig(request);
        }
        var saved = ArgumentCaptor.forClass(MesMdItemBatchConfigDO.class);
        verify(mapper, times(2)).updateById(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(value -> Boolean.TRUE.equals(value.getWorkOrderFlag())));
    }

    @Test void responsePreservesTheSavedDimensionWithoutAnAmbiguousDuplicateProperty() {
        var response = BeanUtils.toBean(new MesMdItemBatchConfigDO().setId(9L).setItemId(1L).setWorkOrderFlag(true), MesMdItemBatchConfigRespVO.class);
        assertTrue(response.getWorkOrderFlag());
        var json = JsonUtils.parseObject(JsonUtils.toJsonString(response), java.util.Map.class);
        assertEquals(Boolean.TRUE, json.get("workOrderFlag"));
        assertFalse(json.containsKey("workorderFlag"));
    }
}
