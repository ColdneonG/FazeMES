package cn.iocoder.yudao.module.infra.dal.mysql.file;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;

/**
 * 文件操作 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface FileMapper extends BaseMapperX<FileDO> {

    /** Used only after an authenticated S3 ticket check; includes unassigned and deleted history. */
    @TenantIgnore
    @Select("SELECT COUNT(*) FROM infra_file WHERE config_id=#{configId} AND path=#{path}")
    long countAnyRegistration(@Param("configId") Long configId, @Param("path") String path);

    default PageResult<FileDO> selectPage(FilePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .likeIfPresent(FileDO::getPath, reqVO.getPath())
                .likeIfPresent(FileDO::getType, reqVO.getType())
                .betweenIfPresent(FileDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(FileDO::getId));
    }

    default FileDO selectLatestByConfigIdAndPath(Long configId, String path) {
        return selectLastOne(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .eq(FileDO::getConfigId, configId)
                .eq(FileDO::getPath, path)
                .orderByAsc(FileDO::getId));
    }

}
