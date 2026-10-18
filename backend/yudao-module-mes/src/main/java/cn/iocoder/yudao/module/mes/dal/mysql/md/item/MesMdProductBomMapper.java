package cn.iocoder.yudao.module.mes.dal.mysql.md.item;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.bom.MesMdProductBomPageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdProductBomDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * MES 产品BOM Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface MesMdProductBomMapper extends BaseMapperX<MesMdProductBomDO> {

    default PageResult<MesMdProductBomDO> selectPage(MesMdProductBomPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MesMdProductBomDO>()
                .eq(MesMdProductBomDO::getItemId, reqVO.getItemId())
                .eqIfPresent(MesMdProductBomDO::getStatus, reqVO.getStatus())
                .orderByDesc(MesMdProductBomDO::getId));
    }

    default List<MesMdProductBomDO> selectByItemId(Long itemId) {
        return selectList(new LambdaQueryWrapperX<MesMdProductBomDO>()
                .eq(MesMdProductBomDO::getItemId, itemId).eq(MesMdProductBomDO::getStatus, 0));
    }

    default List<MesMdProductBomDO> selectByItemIds(Collection<Long> itemIds) {
        return selectList(new LambdaQueryWrapperX<MesMdProductBomDO>()
                .in(MesMdProductBomDO::getItemId, itemIds).eq(MesMdProductBomDO::getStatus, 0));
    }

    default List<MesMdProductBomDO> selectAll() {
        return selectList();
    }

    default List<MesMdProductBomDO> selectAllForUpdate() {
        return selectList(new LambdaQueryWrapperX<MesMdProductBomDO>()
                .orderByAsc(MesMdProductBomDO::getId).last("FOR UPDATE"));
    }

    default void deleteByItemId(Long itemId) {
        delete(MesMdProductBomDO::getItemId, itemId);
    }

}
