package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.MesMdItemSaveReqVO;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MesMdItemStockRangeValidationTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdItemServiceImpl service;
    @Test void negativeThresholdIsRejectedBeforeAnyPersistence() {
        assertThrows(ServiceException.class, () -> service.createItem(new MesMdItemSaveReqVO()
                .setSafeStockFlag(true).setMinStock(BigDecimal.ONE.negate()).setMaxStock(BigDecimal.TEN)));
    }
    @Test void enabledMaximumCannotBeBelowMinimum() {
        assertThrows(ServiceException.class, () -> service.createItem(new MesMdItemSaveReqVO()
                .setSafeStockFlag(true).setMinStock(BigDecimal.TEN).setMaxStock(BigDecimal.ONE)));
    }
}
