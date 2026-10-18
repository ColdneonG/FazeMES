package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.bom.MesMdProductBomSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.*;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdProductBomMapper;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesMdProductBomIntegrityTest {
    @InjectMocks MesMdProductBomServiceImpl service;
    @Mock MesMdProductBomMapper productBomMapper;
    @Mock MesMdItemService itemService;
    @Mock MesProductionIntegrityService integrityService;

    MesMdProductBomSaveReqVO req(Long id, long parent, long child, String quantity) {
        var value = new MesMdProductBomSaveReqVO(); value.setId(id); value.setItemId(parent); value.setBomItemId(child); value.setQuantity(new BigDecimal(quantity)); value.setStatus(0); return value;
    }
    MesMdProductBomDO edge(long id, long parent, long child) { return new MesMdProductBomDO().setId(id).setItemId(parent).setBomItemId(child).setQuantity(BigDecimal.ONE).setStatus(0); }
    @Test void updateCannotCloseAnIndirectCycle() {
        var before = edge(1, 10, 20);
        when(productBomMapper.selectById(1L)).thenReturn(before);
        when(itemService.getItem(anyLong())).thenAnswer(a -> new MesMdItemDO().setId(a.getArgument(0)));
        when(productBomMapper.selectAllForUpdate()).thenReturn(List.of(before, edge(2, 30, 10)));
        assertThrows(ServiceException.class, () -> service.updateProductBom(req(1L,10,30,"1")));
        verify(productBomMapper, never()).updateById(any(MesMdProductBomDO.class));
    }
    @Test void duplicateOrNonPositiveComponentsAreRejectedBeforePersisting() {
        assertThrows(ServiceException.class, () -> service.createProductBom(req(null,10,20,"0")));
        when(itemService.getItem(anyLong())).thenAnswer(a -> new MesMdItemDO().setId(a.getArgument(0)));
        when(productBomMapper.selectAllForUpdate()).thenReturn(List.of(edge(1,10,20)));
        assertThrows(ServiceException.class, () -> service.createProductBom(req(null,10,20,"1")));
        verify(productBomMapper, never()).insert(any(MesMdProductBomDO.class));
    }
}
