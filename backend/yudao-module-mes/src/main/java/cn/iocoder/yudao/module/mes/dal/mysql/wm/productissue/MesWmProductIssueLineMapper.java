package cn.iocoder.yudao.module.mes.dal.mysql.wm.productissue;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.line.MesWmProductIssueLinePageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueLineDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MES 领料出库单行 Mapper
 */
@Mapper
public interface MesWmProductIssueLineMapper extends BaseMapperX<MesWmProductIssueLineDO> {
    default MesWmProductIssueLineDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesWmProductIssueLineDO>().eq(MesWmProductIssueLineDO::getId,id).last("FOR UPDATE"));
    }
    default List<MesWmProductIssueLineDO> selectListByIssueIdForUpdate(Long issueId) {
        return selectList(new LambdaQueryWrapperX<MesWmProductIssueLineDO>().eq(MesWmProductIssueLineDO::getIssueId,issueId).orderByAsc(MesWmProductIssueLineDO::getId).last("FOR UPDATE"));
    }

    default PageResult<MesWmProductIssueLineDO> selectPage(MesWmProductIssueLinePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MesWmProductIssueLineDO>()
                .eqIfPresent(MesWmProductIssueLineDO::getIssueId, reqVO.getIssueId())
                .orderByDesc(MesWmProductIssueLineDO::getId));
    }

    default List<MesWmProductIssueLineDO> selectListByIssueId(Long issueId) {
        return selectList(MesWmProductIssueLineDO::getIssueId, issueId);
    }

    default void deleteByIssueId(Long issueId) {
        delete(MesWmProductIssueLineDO::getIssueId, issueId);
    }

}
