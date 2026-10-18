package cn.iocoder.yudao.module.system.dal.redis.oauth2;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.system.dal.redis.RedisKeyConstants.OAUTH2_ACCESS_TOKEN;
import static cn.iocoder.yudao.module.system.dal.redis.RedisKeyConstants.OAUTH2_REVOKED_TOKEN;

/**
 * {@link OAuth2AccessTokenDO} 的 RedisDAO
 *
 * @author 芋道源码
 */
@Repository
public class OAuth2AccessTokenRedisDAO {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public OAuth2AccessTokenDO get(String accessToken) {
        String redisKey = formatKey(accessToken);
        return JsonUtils.parseObject(stringRedisTemplate.opsForValue().get(redisKey), OAuth2AccessTokenDO.class);
    }

    public void set(OAuth2AccessTokenDO accessTokenDO) {
        String redisKey = formatKey(accessTokenDO.getAccessToken());
        // 清理多余字段，避免缓存
        accessTokenDO.setUpdater(null).setUpdateTime(null).setCreateTime(null).setCreator(null).setDeleted(null);
        long time = LocalDateTimeUtil.between(LocalDateTime.now(), accessTokenDO.getExpiresTime(), ChronoUnit.SECONDS);
        if (time > 0) {
            stringRedisTemplate.opsForValue().set(redisKey, JsonUtils.toJsonString(accessTokenDO), time, TimeUnit.SECONDS);
        }
    }

    public void delete(String accessToken) {
        String redisKey = formatKey(accessToken);
        stringRedisTemplate.delete(redisKey);
    }

    public void deleteList(Collection<String> accessTokens) {
        List<String> redisKeys = CollectionUtils.convertList(accessTokens, OAuth2AccessTokenRedisDAO::formatKey);
        stringRedisTemplate.delete(redisKeys);
    }

    /**
     * Mark a revoked chain before removing its rows. Keep the marker on transaction rollback:
     * an administrator's explicit revocation must fail closed, never revive through a late cache fill.
     * SET NX also prevents a repeated revocation from shortening the first marker's lifetime.
     */
    public void markRevoked(String credential, LocalDateTime expiresTime) {
        long seconds = Math.max(1, Duration.between(LocalDateTime.now(), expiresTime).getSeconds() + 61);
        Boolean stored = stringRedisTemplate.opsForValue().setIfAbsent(revokedKey(credential), "1", seconds, TimeUnit.SECONDS);
        if (stored == null) throw new IllegalStateException("Could not confirm login session revocation");
    }

    /** Shared Redis is checked at the end of credential lookup, including cache hits and late fills. */
    public boolean isRevoked(String accessToken, String refreshToken) {
        List<String> keys = Stream.of(accessToken, refreshToken).filter(Objects::nonNull)
                .filter(token -> !token.isBlank()).map(OAuth2AccessTokenRedisDAO::revokedKey).distinct().toList();
        if (keys.isEmpty()) return false;
        List<String> markers = stringRedisTemplate.opsForValue().multiGet(keys);
        if (markers == null) throw new IllegalStateException("Could not verify login session revocation");
        return markers.stream().anyMatch(Objects::nonNull);
    }

    private static String revokedKey(String credential) {
        return String.format(OAUTH2_REVOKED_TOKEN, DigestUtil.sha256Hex(credential));
    }

    private static String formatKey(String accessToken) {
        return String.format(OAUTH2_ACCESS_TOKEN, accessToken);
    }

}
