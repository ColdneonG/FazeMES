package cn.iocoder.yudao.module.mes.dal.mysql.pro.sop;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.MesSopDocumentDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MesSopDocumentMapper extends BaseMapperX<MesSopDocumentDO> {
    default MesSopDocumentDO lock(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesSopDocumentDO>().eq(MesSopDocumentDO::getId, id).last("FOR UPDATE"));
    }
    default MesSopDocumentDO findBinding(Long itemId, Long processId, boolean lock) {
        var q = new LambdaQueryWrapperX<MesSopDocumentDO>().eq(MesSopDocumentDO::getItemId, itemId).eq(MesSopDocumentDO::getProcessId, processId);
        if (lock) q.last("FOR UPDATE");
        return selectOne(q);
    }
}
