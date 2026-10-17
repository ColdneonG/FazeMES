package cn.iocoder.yudao.module.mes.dal.mysql.dv.task;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.task.MesDvTaskLineDO;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
@Mapper public interface MesDvTaskLineMapper extends BaseMapperX<MesDvTaskLineDO> {
    default List<MesDvTaskLineDO> selectByTask(Long taskId) {
        return selectList(new LambdaQueryWrapperX<MesDvTaskLineDO>().eq(MesDvTaskLineDO::getTaskId,taskId)
            .orderByAsc(MesDvTaskLineDO::getId));
    }
}
