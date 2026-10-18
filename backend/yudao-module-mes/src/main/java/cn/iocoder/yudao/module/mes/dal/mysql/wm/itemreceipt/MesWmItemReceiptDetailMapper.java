package cn.iocoder.yudao.module.mes.dal.mysql.wm.itemreceipt;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemreceipt.MesWmItemReceiptDetailDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MES 采购入库明细 Mapper
 */
@Mapper
public interface MesWmItemReceiptDetailMapper extends BaseMapperX<MesWmItemReceiptDetailDO> {

    default MesWmItemReceiptDetailDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesWmItemReceiptDetailDO>()
                .eq(MesWmItemReceiptDetailDO::getId, id).last("FOR UPDATE"));
    }

    default List<MesWmItemReceiptDetailDO> selectListByReceiptIdForUpdate(Long receiptId) {
        return selectList(new LambdaQueryWrapperX<MesWmItemReceiptDetailDO>()
                .eq(MesWmItemReceiptDetailDO::getReceiptId, receiptId)
                .orderByAsc(MesWmItemReceiptDetailDO::getId).last("FOR UPDATE"));
    }

    default List<MesWmItemReceiptDetailDO> selectListByLineIdForUpdate(Long lineId) {
        return selectList(new LambdaQueryWrapperX<MesWmItemReceiptDetailDO>()
                .eq(MesWmItemReceiptDetailDO::getLineId, lineId)
                .orderByAsc(MesWmItemReceiptDetailDO::getId).last("FOR UPDATE"));
    }

    default List<MesWmItemReceiptDetailDO> selectListByReceiptId(Long receiptId) {
        return selectList(MesWmItemReceiptDetailDO::getReceiptId, receiptId);
    }

    default List<MesWmItemReceiptDetailDO> selectListByLineId(Long lineId) {
        return selectList(MesWmItemReceiptDetailDO::getLineId, lineId);
    }

    default void deleteByReceiptId(Long receiptId) {
        delete(MesWmItemReceiptDetailDO::getReceiptId, receiptId);
    }

    default void deleteByLineId(Long lineId) {
        delete(MesWmItemReceiptDetailDO::getLineId, lineId);
    }

}
