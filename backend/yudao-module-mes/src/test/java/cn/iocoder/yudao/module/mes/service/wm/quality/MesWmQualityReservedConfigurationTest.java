package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.controller.admin.wm.warehouse.vo.MesWmWarehouseSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.warehouse.vo.location.MesWmWarehouseLocationSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.warehouse.vo.area.MesWmWarehouseAreaSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.warehouse.*;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The old code and proposed new code are both protected, so ordinary CRUD cannot remove quarantine. */
class MesWmQualityReservedConfigurationTest {
    @Test void failedOrUnknownInspectionResultCannotBeConvertedToQualifiedInventoryBySubmittedQuantities() {
        assertThrows(ServiceException.class, () -> MesWmQualityPolicy.validateInspectionSplit(
                java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, 2));
        assertThrows(ServiceException.class, () -> MesWmQualityPolicy.validateInspectionSplit(
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN, 1));
        assertThrows(ServiceException.class, () -> MesWmQualityPolicy.validateInspectionSplit(
                java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, null));
        assertDoesNotThrow(() -> MesWmQualityPolicy.validateInspectionSplit(
                java.math.BigDecimal.TEN, new java.math.BigDecimal("8"), new java.math.BigDecimal("2"), 1));
    }
    @Test void ordinaryWarehouseCannotBeRenamedToReservedCodeNorSystemWarehouseRenamedOrDeleted() {
        var mapper = mock(MesWmWarehouseMapper.class);
        var service = new MesWmWarehouseServiceImpl();
        ReflectionTestUtils.setField(service, "warehouseMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseDO().setId(1L).setCode("NORMAL"));
        assertThrows(ServiceException.class, () -> service.updateWarehouse(new MesWmWarehouseSaveReqVO()
                .setId(1L).setCode(MesWmQualityPolicy.QUARANTINE_WAREHOUSE.toLowerCase())));
        assertThrows(ServiceException.class, () -> service.createWarehouse(new MesWmWarehouseSaveReqVO()
                .setCode(MesWmQualityPolicy.QUARANTINE_WAREHOUSE)));
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseDO().setId(1L).setCode(MesWmQualityPolicy.QUARANTINE_WAREHOUSE));
        assertThrows(ServiceException.class, () -> service.updateWarehouse(new MesWmWarehouseSaveReqVO().setId(1L).setCode("NORMAL")));
        assertThrows(ServiceException.class, () -> service.deleteWarehouse(1L));
        verify(mapper, never()).updateById(any(MesWmWarehouseDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test void ordinaryLocationCannotAdoptReservedCodeAndSystemLocationCannotBeMovedOrDeleted() {
        var mapper = mock(MesWmWarehouseLocationMapper.class);
        var service = new MesWmWarehouseLocationServiceImpl();
        ReflectionTestUtils.setField(service, "locationMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseLocationDO().setId(1L).setCode("NORMAL"));
        assertThrows(ServiceException.class, () -> service.updateWarehouseLocation(new MesWmWarehouseLocationSaveReqVO()
                .setId(1L).setWarehouseId(999L).setCode(MesWmQualityPolicy.QUARANTINE_LOCATION)));
        assertThrows(ServiceException.class, () -> service.createWarehouseLocation(new MesWmWarehouseLocationSaveReqVO()
                .setCode(MesWmQualityPolicy.QUARANTINE_LOCATION)));
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseLocationDO().setId(1L).setCode(MesWmQualityPolicy.QUARANTINE_LOCATION));
        assertThrows(ServiceException.class, () -> service.updateWarehouseLocation(new MesWmWarehouseLocationSaveReqVO()
                .setId(1L).setWarehouseId(999L).setCode("NORMAL")));
        assertThrows(ServiceException.class, () -> service.deleteWarehouseLocation(1L));
        verify(mapper, never()).updateById(any(MesWmWarehouseLocationDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test void ordinaryAreaCannotAdoptReservedCodeAndSystemAreaCannotBeMovedOrDeleted() {
        var mapper = mock(MesWmWarehouseAreaMapper.class);
        var service = new MesWmWarehouseAreaServiceImpl();
        ReflectionTestUtils.setField(service, "areaMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseAreaDO().setId(1L).setCode("NORMAL"));
        assertThrows(ServiceException.class, () -> service.updateWarehouseArea(new MesWmWarehouseAreaSaveReqVO()
                .setId(1L).setLocationId(999L).setCode(MesWmQualityPolicy.QUARANTINE_AREA)));
        assertThrows(ServiceException.class, () -> service.createWarehouseArea(new MesWmWarehouseAreaSaveReqVO()
                .setCode(MesWmQualityPolicy.QUARANTINE_AREA)));
        when(mapper.selectById(1L)).thenReturn(new MesWmWarehouseAreaDO().setId(1L).setCode(MesWmQualityPolicy.QUARANTINE_AREA));
        assertThrows(ServiceException.class, () -> service.updateWarehouseArea(new MesWmWarehouseAreaSaveReqVO()
                .setId(1L).setLocationId(999L).setCode("NORMAL")));
        assertThrows(ServiceException.class, () -> service.deleteWarehouseArea(1L));
        verify(mapper, never()).updateById(any(MesWmWarehouseAreaDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }
}
