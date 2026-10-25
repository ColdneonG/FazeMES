package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.FILE_UPLOAD_TICKET_INVALID;

/** One-use evidence of a server-issued S3 upload. Ordinary file registration cannot claim disk objects. */
@Service
public class FileUploadTicketService {
    @Resource private StringRedisTemplate redis;
    static final Duration TTL = Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    @Data public static class Ticket {
        private Long tenantId;
        private Long configId;
        private String path;
        private String name;
        private String url;
    }

    public String issue(Ticket ticket) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), JsonUtils.toJsonString(ticket), TTL);
        return token;
    }

    public Ticket consume(String token, Long tenantId, Long configId, String path, String name) {
        if (token == null || !token.matches("[0-9a-f-]{36}")) throw exception(FILE_UPLOAD_TICKET_INVALID);
        String value = redis.opsForValue().get(key(token));
        Ticket ticket;
        try { ticket = value == null ? null : JsonUtils.parseObject(value, Ticket.class); }
        catch (RuntimeException ex) { throw exception(FILE_UPLOAD_TICKET_INVALID); }
        if (ticket == null || !Objects.equals(tenantId, ticket.getTenantId())
                || !Objects.equals(configId, ticket.getConfigId()) || !Objects.equals(path, ticket.getPath())
                || !Objects.equals(name, ticket.getName())) throw exception(FILE_UPLOAD_TICKET_INVALID);
        if (!Objects.equals(1L, redis.execute(CONSUME, List.of(key(token)), value))) throw exception(FILE_UPLOAD_TICKET_INVALID);
        return ticket;
    }

    static String key(String token) { return "infra:file-upload-ticket:" + token; }
}
