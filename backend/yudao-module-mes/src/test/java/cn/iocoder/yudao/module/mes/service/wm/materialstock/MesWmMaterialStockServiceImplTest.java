package cn.iocoder.yudao.module.mes.service.wm.materialstock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo.MesWmMaterialStockPageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.materialstock.MesWmMaterialStockMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemTypeService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * {@link MesWmMaterialStockServiceImpl} 的单元测试
 *
 * @author 芋道源码
 */
@Import(MesWmMaterialStockServiceImpl.class)
public class MesWmMaterialStockServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MesWmMaterialStockServiceImpl materialStockService;

    @Resource
    private MesWmMaterialStockMapper materialStockMapper;

    @MockitoBean
    private MesMdItemService itemService;
    @MockitoBean
    private MesMdItemTypeService itemTypeService;
    @MockitoBean
    private MesWmWarehouseAreaService areaService;
    @MockitoBean
    private MesWmWarehouseService warehouseService;

    @Test
    public void testGetMaterialStockPage_virtualFilterOnly() {
        // 准备数据：虚拟仓库存 + 普通仓库存
        Long virtualWarehouseId = 100L;
        Long normalWarehouseId = 200L;
        MesWmMaterialStockDO virtualStock = createMaterialStockPojo(o -> o.setWarehouseId(virtualWarehouseId));
        MesWmMaterialStockDO normalStock = createMaterialStockPojo(o -> o.setWarehouseId(normalWarehouseId));
        materialStockMapper.insert(virtualStock);
        materialStockMapper.insert(normalStock);
        mockVirtualWarehouse(virtualWarehouseId);

        // 调用：只看虚拟仓
        MesWmMaterialStockPageReqVO reqVO = new MesWmMaterialStockPageReqVO();
        reqVO.setVirtualFilter(MesWmMaterialStockPageReqVO.VIRTUAL_FILTER_ONLY);
        PageResult<MesWmMaterialStockDO> result = materialStockService.getMaterialStockPage(reqVO);

        // 断言：只返回虚拟仓库存，且 total 与列表一致
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals(virtualStock.getId(), result.getList().get(0).getId());
    }

    @Test
    public void testGetMaterialStockPage_virtualFilterExclude() {
        // 准备数据：虚拟仓库存 + 普通仓库存
        Long virtualWarehouseId = 100L;
        Long normalWarehouseId = 200L;
        MesWmMaterialStockDO virtualStock = createMaterialStockPojo(o -> o.setWarehouseId(virtualWarehouseId));
        MesWmMaterialStockDO normalStock = createMaterialStockPojo(o -> o.setWarehouseId(normalWarehouseId));
        materialStockMapper.insert(virtualStock);
        materialStockMapper.insert(normalStock);
        mockVirtualWarehouse(virtualWarehouseId);

        // 调用：排除虚拟仓
        MesWmMaterialStockPageReqVO reqVO = new MesWmMaterialStockPageReqVO();
        reqVO.setVirtualFilter(MesWmMaterialStockPageReqVO.VIRTUAL_FILTER_EXCLUDE);
        PageResult<MesWmMaterialStockDO> result = materialStockService.getMaterialStockPage(reqVO);

        // 断言：只返回普通仓库存，且 total 与列表一致
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals(normalStock.getId(), result.getList().get(0).getId());
    }

    private MesWmMaterialStockDO createMaterialStockPojo(Consumer<MesWmMaterialStockDO> consumer) {
        return randomPojo(MesWmMaterialStockDO.class, o -> {
            o.setQuantity(new BigDecimal("10.00"));
            o.setReceiptTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            o.setFrozen(false);
            o.setDeleted(false);
            consumer.accept(o);
        });
    }

    private void mockVirtualWarehouse(Long virtualWarehouseId) {
        MesWmWarehouseDO virtualWarehouse = new MesWmWarehouseDO();
        virtualWarehouse.setId(virtualWarehouseId);
        when(warehouseService.getWarehouseByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE))
                .thenReturn(virtualWarehouse);
    }

    @Test
    public void workOrderFilterExcludesOtherOrdersAndUnassignedStock() {
        var owned = createMaterialStockPojo(s -> s.setWorkOrderId(101L));
        var other = createMaterialStockPojo(s -> s.setWorkOrderId(102L));
        var legacy = createMaterialStockPojo(s -> s.setWorkOrderId(null));
        materialStockMapper.insert(owned);
        materialStockMapper.insert(other);
        materialStockMapper.insert(legacy);
        var query = new MesWmMaterialStockPageReqVO();
        query.setWorkOrderId(101L);
        var result = materialStockService.getMaterialStockPage(query);
        assertEquals(1L, result.getTotal());
        assertEquals(owned.getId(), result.getList().get(0).getId());
    }

    @Test
    public void sameItemBatchAndLocationRemainSeparateByOrderAndVendor() {
        var a = MesWmMaterialStockDO.builder().itemId(1L).warehouseId(2L).locationId(3L).areaId(4L)
                .batchId(null).vendorId(10L).workOrderId(100L).quantity(BigDecimal.TEN).build();
        var b = MesWmMaterialStockDO.builder().itemId(1L).warehouseId(2L).locationId(3L).areaId(4L)
                .batchId(null).vendorId(10L).workOrderId(101L).quantity(BigDecimal.TEN).build();
        var c = MesWmMaterialStockDO.builder().itemId(1L).warehouseId(2L).locationId(3L).areaId(4L)
                .batchId(null).vendorId(11L).workOrderId(100L).quantity(BigDecimal.TEN).build();
        materialStockMapper.insert(a);
        materialStockMapper.insert(b);
        materialStockMapper.insert(c);
        assertEquals(a.getId(), materialStockMapper.selectByCompositeKey(1L, 2L, 3L, 4L, null, 10L, 100L).getId());
        assertEquals(b.getId(), materialStockMapper.selectByCompositeKey(1L, 2L, 3L, 4L, null, 10L, 101L).getId());
        assertEquals(c.getId(), materialStockMapper.selectByCompositeKey(1L, 2L, 3L, 4L, null, 11L, 100L).getId());
        assertNull(materialStockMapper.selectByCompositeKey(1L, 2L, 3L, 4L, null, null, 100L));
    }

    @Test
    public void competingDeductionsCannotProduceNegativeBalance() {
        var stock = createMaterialStockPojo(s -> s.setQuantity(BigDecimal.TEN));
        materialStockMapper.insert(stock);
        assertEquals(1, materialStockMapper.updateQuantity(stock.getId(), new BigDecimal("-8"), true));
        assertEquals(0, materialStockMapper.updateQuantity(stock.getId(), new BigDecimal("-8"), true));
        assertEquals(0, new BigDecimal("2").compareTo(materialStockMapper.selectById(stock.getId()).getQuantity()));
    }

}
