package cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnissue.vo.line.MesWmReturnIssueLinePageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueLineDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MES 生产退料单行 Mapper
 */
@Mapper
public interface MesWmReturnIssueLineMapper extends BaseMapperX<MesWmReturnIssueLineDO> {
    default List<MesWmReturnIssueLineDO> selectListByIssueIdForUpdate(Long parentId) {
        return selectList(new LambdaQueryWrapperX<MesWmReturnIssueLineDO>()
                .eq(MesWmReturnIssueLineDO::getIssueId, parentId).orderByAsc(MesWmReturnIssueLineDO::getId).last("FOR UPDATE"));
    }

    default MesWmReturnIssueLineDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MesWmReturnIssueLineDO>()
                .eq(MesWmReturnIssueLineDO::getId, id).last("FOR UPDATE"));
    }


    default PageResult<MesWmReturnIssueLineDO> selectPage(MesWmReturnIssueLinePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MesWmReturnIssueLineDO>()
                .eqIfPresent(MesWmReturnIssueLineDO::getIssueId, reqVO.getIssueId())
                .orderByDesc(MesWmReturnIssueLineDO::getId));
    }

    default List<MesWmReturnIssueLineDO> selectListByIssueId(Long issueId) {
        return selectList(MesWmReturnIssueLineDO::getIssueId, issueId);
    }

    default void deleteByIssueId(Long issueId) {
        delete(MesWmReturnIssueLineDO::getIssueId, issueId);
    }

}
