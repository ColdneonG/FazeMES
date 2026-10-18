package cn.iocoder.yudao.module.mes.service.face;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

@Component
public class FaceChallengeStore {
    @Resource private StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]);end;return n",Long.class);
    private static final DefaultRedisScript<String> TAKE=new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]);redis.call('DEL',KEYS[1]);return v",String.class);
    public static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException();}
    }
    public void limit(String scope,int seconds,int count) {
        Long number=redis.execute(LIMIT,List.of("mes:face:limit:"+scope),String.valueOf(seconds));
        if(number==null||number>count)throw new IllegalArgumentException("Too many face attempts; try later");
    }
    private String binding(long tenant,String username,String ip){return tenant+":"+digest(username)+":"+digest(ip);}
    public String issue(long tenant,String username,String ip) {
        limit("issue:"+tenant+":"+digest(ip),60,8);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set("mes:face:challenge:"+digest(token),binding(tenant,username,ip),Duration.ofSeconds(60));
        return token;
    }
    public boolean consume(long tenant,String username,String ip,String token) {
        limit("login-user:"+tenant+":"+digest(username),300,5);
        limit("login-ip:"+tenant+":"+digest(ip),300,20);
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return false;
        String stored=redis.execute(TAKE,List.of("mes:face:challenge:"+digest(token)));
        return stored!=null&&MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),binding(tenant,username,ip).getBytes(StandardCharsets.UTF_8));
    }
}
