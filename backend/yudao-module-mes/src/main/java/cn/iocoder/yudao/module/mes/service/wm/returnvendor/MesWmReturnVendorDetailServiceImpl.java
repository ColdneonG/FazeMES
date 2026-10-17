package cn.iocoder.yudao.module.mes.service.wm.returnvendor;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnvendor.vo.detail.MesWmReturnVendorDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnvendor.MesWmReturnVendorDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnvendor.MesWmReturnVendorLineDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnvendor.MesWmReturnVendorDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnVendorStatusEnum;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnvendor.MesWmReturnVendorDetailMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 供应商退货明细 Service 实现类
 */
@Service
@Validated
public class MesWmReturnVendorDetailServiceImpl implements MesWmReturnVendorDetailService {

    @Resource
    private MesWmReturnVendorDetailMapper returnVendorDetailMapper;

    @Resource
    @Lazy
    private MesWmReturnVendorLineService returnVendorLineService;
    @Resource
    @Lazy
    private MesWmReturnVendorService returnVendorService;
    @Resource
    private MesMdItemService itemService;
    @Resource
    private MesWmMaterialStockService materialStockService;
    @Resource
    private MesWmWarehouseAreaService warehouseAreaService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createReturnVendorDetail(MesWmReturnVendorDetailSaveReqVO createReqVO) {
        // 校验数据
        validateDetailSaveData(createReqVO);

        // 插入
        MesWmReturnVendorDetailDO detail = BeanUtils.toBean(createReqVO, MesWmReturnVendorDetailDO.class);
        returnVendorDetailMapper.insert(detail);
        return detail.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateReturnVendorDetail(MesWmReturnVendorDetailSaveReqVO updateReqVO) {
        // 校验存在
        validateReturnVendorDetailExists(updateReqVO.getId());
        // 校验数据
        validateDetailSaveData(updateReqVO);

        // 更新
        MesWmReturnVendorDetailDO updateObj = BeanUtils.toBean(updateReqVO, MesWmReturnVendorDetailDO.class);
        returnVendorDetailMapper.updateById(updateObj);
    }

    /**
     * 校验明细创建/修改的公共数据
     */
    private void validateDetailSaveData(MesWmReturnVendorDetailSaveReqVO reqVO) {
        MesWmReturnVendorDO returnVendor = returnVendorService.validateReturnVendorExists(reqVO.getReturnId());
        if (!MesWmReturnVendorStatusEnum.APPROVING.getStatus().equals(returnVendor.getStatus())) {
            throw exception(WM_RETURN_VENDOR_STATUS_INVALID);
        }
        // 校验父数据（行）存在
        MesWmReturnVendorLineDO line = returnVendorLineService.validateReturnVendorLineExists(reqVO.getLineId());
        if (ObjUtil.notEqual(line.getReturnId(), reqVO.getReturnId())) {
            throw exception(WM_RETURN_VENDOR_DETAIL_LINE_NOT_MATCH);
        }
        // 校验物料存在
        itemService.validateItemExistsAndEnable(reqVO.getItemId());
        // 校验物料与行物料一致
        validateItemConsistency(reqVO.getItemId(), line);
        // 校验库位存在
        warehouseAreaService.validateWarehouseAreaExists(
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId());
        // 校验库存记录存在且物料一致
        validateMaterialStock(reqVO, line, returnVendor);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteReturnVendorDetail(Long id) {
        // 校验存在
        validateReturnVendorDetailExists(id);
        MesWmReturnVendorDetailDO detail = returnVendorDetailMapper.selectById(id);
        MesWmReturnVendorDO returnVendor = returnVendorService.validateReturnVendorExists(detail.getReturnId());
        if (!MesWmReturnVendorStatusEnum.APPROVING.getStatus().equals(returnVendor.getStatus())) {
            throw exception(WM_RETURN_VENDOR_STATUS_INVALID);
        }
        // 删除
        returnVendorDetailMapper.deleteById(id);
    }

    @Override
    public MesWmReturnVendorDetailDO getReturnVendorDetail(Long id) {
        return returnVendorDetailMapper.selectById(id);
    }

    @Override
    public List<MesWmReturnVendorDetailDO> getReturnVendorDetailListByLineId(Long lineId) {
        return returnVendorDetailMapper.selectListByLineId(lineId);
    }

    @Override
    public List<MesWmReturnVendorDetailDO> getReturnVendorDetailListByReturnId(Long returnId) {
        return returnVendorDetailMapper.selectListByReturnId(returnId);
    }

    @Override
    public void deleteReturnVendorDetailByReturnId(Long returnId) {
        returnVendorDetailMapper.deleteByReturnId(returnId);
    }

    @Override
    public void deleteReturnVendorDetailByLineId(Long lineId) {
        returnVendorDetailMapper.deleteByLineId(lineId);
    }

    private void validateReturnVendorDetailExists(Long id) {
        if (returnVendorDetailMapper.selectById(id) == null) {
            throw exception(WM_RETURN_VENDOR_DETAIL_NOT_EXISTS);
        }
    }

    /**
     * 校验明细物料与行物料一致
     */
    private void validateItemConsistency(Long detailItemId, MesWmReturnVendorLineDO line) {
        if (ObjUtil.notEqual(detailItemId, line.getItemId())) {
            throw exception(WM_RETURN_VENDOR_DETAIL_ITEM_MISMATCH);
        }
    }

    /**
     * 校验库存记录存在且物料与行物料一致
     */
    private void validateMaterialStock(MesWmReturnVendorDetailSaveReqVO reqVO, MesWmReturnVendorLineDO line,
                                       MesWmReturnVendorDO returnVendor) {
        MesWmMaterialStockDO stock = materialStockService.validateSelectedStock(
                reqVO.getMaterialStockId(), reqVO.getItemId(), reqVO.getBatchId(), reqVO.getBatchCode(),
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId(), reqVO.getQuantity());
        if (ObjUtil.notEqual(line.getItemId(), reqVO.getItemId())) {
            throw exception(WM_RETURN_VENDOR_DETAIL_ITEM_MISMATCH);
        }
        if (stock == null || ObjUtil.notEqual(stock.getVendorId(), returnVendor.getVendorId())) {
            throw exception(WM_RETURN_VENDOR_STOCK_VENDOR_MISMATCH);
        }
        if (ObjUtil.notEqual(line.getBatchId(), reqVO.getBatchId())
                || (StrUtil.isNotBlank(line.getBatchCode()) && ObjUtil.notEqual(line.getBatchCode(), reqVO.getBatchCode()))) {
            throw exception(WM_RETURN_VENDOR_DETAIL_BATCH_MISMATCH);
        }
    }

    @Override
    public void validateReturnVendorDetails(Long returnId) {
        MesWmReturnVendorDO returnVendor = returnVendorService.validateReturnVendorExists(returnId);
        for (MesWmReturnVendorDetailDO detail : getReturnVendorDetailListByReturnId(returnId)) {
            MesWmReturnVendorLineDO line = returnVendorLineService.validateReturnVendorLineExists(detail.getLineId());
            if (ObjUtil.notEqual(line.getReturnId(), returnId) || ObjUtil.notEqual(detail.getReturnId(), returnId)) {
                throw exception(WM_RETURN_VENDOR_DETAIL_LINE_NOT_MATCH);
            }
            validateMaterialStock(BeanUtils.toBean(detail, MesWmReturnVendorDetailSaveReqVO.class), line, returnVendor);
        }
    }

}
