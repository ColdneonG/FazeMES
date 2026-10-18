package cn.iocoder.yudao.module.mes.dal.mysql.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopVersionDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MesSopVersionMapper extends BaseMapperX<MesSopVersionDO> {
    default MesSopVersionDO lock(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesSopVersionDO>().eq(MesSopVersionDO::getId, id).last("FOR UPDATE"));
    }
}
