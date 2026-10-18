package cn.iocoder.yudao.module.mes.dal.mysql.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopTaskSnapshotDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MesSopTaskSnapshotMapper extends BaseMapperX<MesSopTaskSnapshotDO> {
    default MesSopTaskSnapshotDO lock(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesSopTaskSnapshotDO>().eq(MesSopTaskSnapshotDO::getId, id).last("FOR UPDATE"));
    }
    default MesSopTaskSnapshotDO findTask(Long taskId) {
        return selectOne(MesSopTaskSnapshotDO::getTaskId, taskId);
    }
}
