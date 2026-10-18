package cn.iocoder.yudao.module.mes.dal.mysql.wm.productproduce;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productproduce.MesWmProductProduceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MES 生产入库单 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface MesWmProductProduceMapper extends BaseMapperX<MesWmProductProduceDO> {

    default MesWmProductProduceDO selectByFeedbackId(Long feedbackId) {
        return selectOne(MesWmProductProduceDO::getFeedbackId, feedbackId);
    }

    default MesWmProductProduceDO selectByFeedbackIdForUpdate(Long feedbackId) {
        return selectOne(new LambdaQueryWrapperX<MesWmProductProduceDO>()
                .eq(MesWmProductProduceDO::getFeedbackId, feedbackId).last("FOR UPDATE"));
    }

    default MesWmProductProduceDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesWmProductProduceDO>()
                .eq(MesWmProductProduceDO::getId, id).last("FOR UPDATE"));
    }

}
