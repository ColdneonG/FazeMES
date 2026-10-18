package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.MesMdStockWarningRespVO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdStockWarningMapper;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMdStockWarningServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdStockWarningService service;
    @Mock private MesMdStockWarningMapper mapper;
    private MesMdStockWarningRespVO stock(int amount, int min, int max) {
        return new MesMdStockWarningRespVO().setStockQuantity(BigDecimal.valueOf(amount))
                .setMinStock(BigDecimal.valueOf(min)).setMaxStock(BigDecimal.valueOf(max));
    }
    @Test void warnsOutsideBoundsAndTreatsZeroMaximumAsDisabled() {
        when(mapper.selectStockLevels(null)).thenReturn(List.of(stock(0,5,10),stock(5,5,10),stock(10,5,10),stock(11,5,10),stock(100,0,0),stock(-2,0,10)));
        var rows = service.getWarnings(null,null);
        assertEquals(3,rows.size());
        assertEquals(List.of("LOW","HIGH","LOW"),rows.stream().map(MesMdStockWarningRespVO::getLevel).toList());
        assertEquals(0,BigDecimal.valueOf(5).compareTo(rows.get(0).getDifference()));
    }
    @Test void warningLevelFilterDoesNotReturnOtherKinds() {
        when(mapper.selectStockLevels("A")).thenReturn(List.of(stock(0,1,10),stock(11,1,10)));
        var rows = service.getWarnings("A","HIGH");
        assertEquals(1,rows.size()); assertEquals("HIGH",rows.get(0).getLevel());
    }
    @Test void unknownFilterIsRejectedBeforeQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.getWarnings(null,"anything"));
        verifyNoInteractions(mapper);
    }
}
