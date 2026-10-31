package cn.iocoder.yudao.module.mes.dal.redis.md.autocode;

import cn.iocoder.yudao.module.mes.dal.redis.RedisKeyConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * MES 编码规则的 Redis DAO
 *
 * @author 芋道源码
 */
@Repository
public class MesMdAutoCodeRedisDAO {

    // Do not rewind another caller's reservation or convert a cycling key to a permanent one.
    // Compare positive integer strings without Lua floating point rounding.
    private static final DefaultRedisScript<Long> ADVANCE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            local floor = ARGV[1]
            if v and (#v < #floor or (#v == #floor and v < floor)) then
                local ttl = redis.call('PTTL', KEYS[1])
                redis.call('SET', KEYS[1], floor)
                if ttl >= 0 then redis.call('PEXPIRE', KEYS[1], math.max(1,ttl)) end
                return 1
            end
            return 0
            """, Long.class);

    public void advanceIfBehind(String keySuffix, long floor) {
        stringRedisTemplate.execute(ADVANCE, List.of(RedisKeyConstants.AUTO_CODE + keySuffix), Long.toString(floor));
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 递增序号（带初始值和循环）
     *
     * @param keySuffix key 后缀（不包含 prefix）
     * @param duration 过期时间
     * @param step 步长
     * @param start 起始值（仅在 key 不存在时使用）
     * @return 递增后的值
     */
    public Long increment(String keySuffix, Duration duration,  Integer start, Integer step) {
        String key = RedisKeyConstants.AUTO_CODE + keySuffix;

        // 情况一：如果 key 不存在，初始化为 startNo
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(start));
        if (Boolean.TRUE.equals(success)) {
            // 设置成功，说明是第一次，设置过期时间并返回 startNo
            if (duration != null) {
                stringRedisTemplate.expire(key, duration);
            }
            return start.longValue();
        }

        // 情况二：key 已存在，递增
        Long value = stringRedisTemplate.opsForValue().increment(key, step);
        if (duration != null) {
            stringRedisTemplate.expire(key, duration);
        }
        return value;
    }

}
