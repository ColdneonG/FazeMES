package cn.iocoder.yudao.module.mes.dal.mysql.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopStepRecordDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MesSopStepRecordMapper extends BaseMapperX<MesSopStepRecordDO> {
    default MesSopStepRecordDO lock(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesSopStepRecordDO>().eq(MesSopStepRecordDO::getId, id).last("FOR UPDATE"));
    }
    default MesSopStepRecordDO findStep(Long snapshotId, String stepId) {
        return selectOne(new LambdaQueryWrapperX<MesSopStepRecordDO>().eq(MesSopStepRecordDO::getSnapshotId, snapshotId).eq(MesSopStepRecordDO::getStepId, stepId));
    }
}
