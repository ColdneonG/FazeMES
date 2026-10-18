package cn.iocoder.yudao.module.system.dal.mysql.online;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.system.controller.admin.online.vo.OnlineSessionPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.List;
@Mapper
public interface OnlineSessionMapper extends BaseMapperX<OAuth2AccessTokenDO> {
    default PageResult<OAuth2AccessTokenDO> selectSessions(OnlineSessionPageReqVO q,Long tenantId) {
        return selectPage(q,new LambdaQueryWrapperX<OAuth2AccessTokenDO>()
            .select(OAuth2AccessTokenDO::getId,OAuth2AccessTokenDO::getUserId,OAuth2AccessTokenDO::getClientId,
                OAuth2AccessTokenDO::getCreateTime,OAuth2AccessTokenDO::getExpiresTime)
            .eq(OAuth2AccessTokenDO::getTenantId,tenantId).eq(OAuth2AccessTokenDO::getUserType,2)
            .eq(q.getUserId()!=null,OAuth2AccessTokenDO::getUserId,q.getUserId())
            .like(q.getClientId()!=null && !q.getClientId().isBlank(),OAuth2AccessTokenDO::getClientId,q.getClientId())
            .gt(OAuth2AccessTokenDO::getExpiresTime,LocalDateTime.now()).orderByDesc(OAuth2AccessTokenDO::getId));
    }
    default OAuth2AccessTokenDO selectSession(Long id,Long tenantId) {
        return selectOne(new LambdaQueryWrapperX<OAuth2AccessTokenDO>().eq(OAuth2AccessTokenDO::getId,id)
            .eq(OAuth2AccessTokenDO::getTenantId,tenantId).eq(OAuth2AccessTokenDO::getUserType,2));
    }
    default List<OAuth2AccessTokenDO> selectChainForUpdate(String refreshToken,Long tenantId) {
        return selectList(new LambdaQueryWrapperX<OAuth2AccessTokenDO>().eq(OAuth2AccessTokenDO::getRefreshToken,refreshToken)
            .eq(OAuth2AccessTokenDO::getTenantId,tenantId).eq(OAuth2AccessTokenDO::getUserType,2)
            .orderByAsc(OAuth2AccessTokenDO::getId).last("FOR UPDATE"));
    }
}
