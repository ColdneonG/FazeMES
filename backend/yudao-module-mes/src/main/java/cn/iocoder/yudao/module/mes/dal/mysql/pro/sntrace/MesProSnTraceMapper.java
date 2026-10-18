package cn.iocoder.yudao.module.mes.dal.mysql.pro.sntrace;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.MesProSnTraceDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MesProSnTraceMapper extends BaseMapperX<MesProSnTraceDO> {
    default MesProSnTraceDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getId, id).last("FOR UPDATE"));
    }
}
