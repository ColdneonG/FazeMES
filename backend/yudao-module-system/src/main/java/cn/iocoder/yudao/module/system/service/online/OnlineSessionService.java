package cn.iocoder.yudao.module.system.service.online;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.controller.admin.online.vo.*;
import cn.iocoder.yudao.module.system.dal.mysql.online.OnlineSessionMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
@Service @Slf4j
public class OnlineSessionService {
    @Resource private OnlineSessionMapper sessionMapper;
    @Resource private OAuth2RefreshTokenMapper refreshTokenMapper;
    @Resource private OAuth2AccessTokenRedisDAO tokenRedisDAO;
    @Resource private AdminUserService userService;
    public PageResult<OnlineSessionRespVO> page(OnlineSessionPageReqVO q) {
        var page=sessionMapper.selectSessions(q,TenantContextHolder.getRequiredTenantId());
        var users=userService.getUserMap(page.getList().stream().map(OAuth2AccessTokenDO::getUserId).collect(Collectors.toSet()));
        List<OnlineSessionRespVO> rows=page.getList().stream().map(session->{
            var user=users.get(session.getUserId());
            return new OnlineSessionRespVO().setId(session.getId()).setUserId(session.getUserId())
                .setClientId(session.getClientId()).setCreateTime(session.getCreateTime()).setExpiresTime(session.getExpiresTime())
                .setUsername(user==null?null:user.getUsername()).setNickname(user==null?null:user.getNickname());
        }).toList();
        return new PageResult<>(rows,page.getTotal());
    }
    protected OAuth2AccessTokenDO findSession(Long id,Long tenantId) {return sessionMapper.selectSession(id,tenantId);}
    @Transactional(rollbackFor=Exception.class)
    public boolean revoke(Long id) {
        Long tenantId=TenantContextHolder.getRequiredTenantId();
        OAuth2AccessTokenDO snapshot=findSession(id,tenantId);
        if(snapshot==null) return false;
        if(snapshot.getRefreshToken()==null || snapshot.getRefreshToken().isBlank()) {
            tokenRedisDAO.markRevoked(snapshot.getAccessToken(), latestExpiry(List.of(snapshot), null));
            sessionMapper.deleteById(snapshot.getId());tokenRedisDAO.delete(snapshot.getAccessToken());return true;
        }
        // Same first lock as refreshAccessToken. Current reads below see tokens issued while waiting.
        var refresh=refreshTokenMapper.selectByRefreshTokenForUpdate(snapshot.getRefreshToken());
        if(refresh!=null && !Objects.equals(refresh.getTenantId(),tenantId)) return false;
        var chain=sessionMapper.selectChainForUpdate(snapshot.getRefreshToken(),tenantId);
        var expiry=latestExpiry(chain, refresh==null?null:refresh.getExpiresTime());
        if(snapshot.getExpiresTime()!=null && snapshot.getExpiresTime().isAfter(expiry)) expiry=snapshot.getExpiresTime();
        // One chain marker also rejects older cached access tokens no longer present in the database.
        // Do not remove this marker after rollback: explicit revocations remain fail-closed.
        tokenRedisDAO.markRevoked(snapshot.getRefreshToken(), expiry);
        if(!chain.isEmpty()) sessionMapper.deleteByIds(chain.stream().map(OAuth2AccessTokenDO::getId).toList());
        refreshTokenMapper.deleteByRefreshToken(snapshot.getRefreshToken());
        tokenRedisDAO.deleteList(chain.stream().map(OAuth2AccessTokenDO::getAccessToken).toList());
        tokenRedisDAO.delete(snapshot.getRefreshToken());
        log.info("Administrator {} revoked login session {} for user {} in tenant {}",getLoginUserId(),id,snapshot.getUserId(),tenantId);
        return true;
    }
    private LocalDateTime latestExpiry(List<OAuth2AccessTokenDO> chain,LocalDateTime refreshExpiry) {
        LocalDateTime expiry=refreshExpiry==null?LocalDateTime.now():refreshExpiry;
        for(var access:chain) if(access.getExpiresTime()!=null && access.getExpiresTime().isAfter(expiry)) expiry=access.getExpiresTime();
        return expiry;
    }
}
