package cn.iocoder.yudao.module.mes.dal.mysql.dv.task;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.dv.task.vo.MesDvTaskPageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.MesDvTaskDO;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
@Mapper public interface MesDvTaskMapper extends BaseMapperX<MesDvTaskDO> {
    default MesDvTaskDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesDvTaskDO>().eq(MesDvTaskDO::getId,id).last("FOR UPDATE"));
    }
    default MesDvTaskDO selectOccurrence(Long planId, Long machineryId, LocalDateTime due) {
        return selectOne(new LambdaQueryWrapperX<MesDvTaskDO>().eq(MesDvTaskDO::getPlanId,planId)
            .eq(MesDvTaskDO::getMachineryId,machineryId).eq(MesDvTaskDO::getDueTime,due));
    }
    default PageResult<MesDvTaskDO> selectPage(MesDvTaskPageReqVO q) {
        return selectPage(q,new LambdaQueryWrapperX<MesDvTaskDO>().eqIfPresent(MesDvTaskDO::getPlanId,q.getPlanId())
            .eqIfPresent(MesDvTaskDO::getMachineryId,q.getMachineryId()).eqIfPresent(MesDvTaskDO::getStatus,q.getStatus())
            .eqIfPresent(MesDvTaskDO::getUserId,q.getUserId()).orderByAsc(MesDvTaskDO::getDueTime,MesDvTaskDO::getId));
    }
}
