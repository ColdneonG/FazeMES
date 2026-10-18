package cn.iocoder.yudao.module.mes.service.wm.returnvendor;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnvendor.vo.detail.MesWmReturnVendorDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnvendor.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnvendor.MesWmReturnVendorDetailMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnVendorStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesWmReturnVendorStockValidationTest {
    @InjectMocks private MesWmReturnVendorDetailServiceImpl service;
    @Mock private MesWmReturnVendorDetailMapper mapper;
    @Mock private MesWmReturnVendorLineService lineService;
    @Mock private MesWmReturnVendorService returnVendorService;
    @Mock private MesWmMaterialStockService stockService;
    @Mock private MesMdItemService itemService;
    @Mock private MesWmWarehouseAreaService areaService;

    private MesWmReturnVendorDO header;
    private MesWmReturnVendorLineDO line;
    private MesWmMaterialStockDO stock;
    private MesWmReturnVendorDetailSaveReqVO req;

    @BeforeEach
    void prepare() {
        header = new MesWmReturnVendorDO().setId(1L).setVendorId(10L)
                .setStatus(MesWmReturnVendorStatusEnum.APPROVING.getStatus());
        line = new MesWmReturnVendorLineDO().setId(2L).setReturnId(1L).setItemId(3L)
                .setBatchId(4L).setBatchCode("BATCH-4");
        stock = new MesWmMaterialStockDO().setId(5L).setItemId(3L).setVendorId(10L)
                .setBatchId(4L).setBatchCode("BATCH-4");
        req = new MesWmReturnVendorDetailSaveReqVO();
        req.setReturnId(1L);
        req.setLineId(2L);
        req.setItemId(3L);
        req.setMaterialStockId(5L);
        req.setBatchId(4L);
        req.setBatchCode("BATCH-4");
        req.setWarehouseId(6L);
        req.setLocationId(7L);
        req.setAreaId(8L);
        req.setQuantity(BigDecimal.ONE);
        lenient().when(returnVendorService.validateReturnVendorExists(1L)).thenReturn(header);
        lenient().when(lineService.validateReturnVendorLineExists(2L)).thenReturn(line);
        lenient().when(stockService.validateSelectedStock(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(stock);
    }

    @Test
    void validSupplierAndLineBatchCanBePicked() {
        assertDoesNotThrow(() -> service.createReturnVendorDetail(req));
        verify(mapper).insert(any(MesWmReturnVendorDetailDO.class));
    }

    @Test
    void inventoryFromAnotherSupplierIsRejected() {
        stock.setVendorId(20L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createReturnVendorDetail(req));
        assertEquals(WM_RETURN_VENDOR_STOCK_VENDOR_MISMATCH.getCode(), ex.getCode());
        verify(mapper, never()).insert(any(MesWmReturnVendorDetailDO.class));
    }

    @Test
    void detailCannotChooseDifferentBatchThanLine() {
        req.setBatchId(99L).setBatchCode("OTHER");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createReturnVendorDetail(req));
        assertEquals(WM_RETURN_VENDOR_DETAIL_BATCH_MISMATCH.getCode(), ex.getCode());
        verify(mapper, never()).insert(any(MesWmReturnVendorDetailDO.class));
    }

    @Test
    void batchlessLineCannotPickBatchedStock() {
        line.setBatchId(null).setBatchCode(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createReturnVendorDetail(req));
        assertEquals(WM_RETURN_VENDOR_DETAIL_BATCH_MISMATCH.getCode(), ex.getCode());
    }

    @Test
    void finishedDocumentCannotAcquireNewDetails() {
        header.setStatus(MesWmReturnVendorStatusEnum.FINISHED.getStatus());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createReturnVendorDetail(req));
        assertEquals(WM_RETURN_VENDOR_STATUS_INVALID.getCode(), ex.getCode());
        verify(mapper, never()).insert(any(MesWmReturnVendorDetailDO.class));
    }

    @Test
    void executionRevalidatesOldDetailsAfterHeaderSupplierChanges() {
        MesWmReturnVendorDetailDO detail = BeanUtils.toBean(req, MesWmReturnVendorDetailDO.class);
        when(mapper.selectListByReturnId(1L)).thenReturn(List.of(detail));
        header.setVendorId(20L).setStatus(MesWmReturnVendorStatusEnum.APPROVED.getStatus());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.validateReturnVendorDetails(1L));
        assertEquals(WM_RETURN_VENDOR_STOCK_VENDOR_MISMATCH.getCode(), ex.getCode());
    }

    @Test
    void executionRevalidatesOldDetailsAfterLineBatchChanges() {
        MesWmReturnVendorDetailDO detail = BeanUtils.toBean(req, MesWmReturnVendorDetailDO.class);
        when(mapper.selectListByReturnId(1L)).thenReturn(List.of(detail));
        line.setBatchId(99L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.validateReturnVendorDetails(1L));
        assertEquals(WM_RETURN_VENDOR_DETAIL_BATCH_MISMATCH.getCode(), ex.getCode());
    }
}
