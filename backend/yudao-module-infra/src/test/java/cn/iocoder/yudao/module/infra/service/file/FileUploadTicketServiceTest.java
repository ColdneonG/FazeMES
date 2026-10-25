package cn.iocoder.yudao.module.infra.service.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileUploadTicketServiceTest {
    final FileUploadTicketService service = new FileUploadTicketService();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String, String> values = mock(ValueOperations.class);
    final Map<String, String> store = new HashMap<>();

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "redis", redis);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(a -> { store.put(a.getArgument(0), a.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(a -> store.get(a.getArgument(0)));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenAnswer(a -> {
            String key = ((List<String>) a.getArgument(1)).get(0);
            if (!java.util.Objects.equals(store.get(key), a.getArgument(2))) return 0L;
            store.remove(key); return 1L;
        });
    }

    String issue() {
        return service.issue(new FileUploadTicketService.Ticket().setTenantId(1L).setConfigId(29L)
                .setPath("tenant/1/new.jpg").setName("new.jpg").setUrl("https://trusted.test/new.jpg"));
    }

    @Test void ticketBindsTenantStoragePathAndNameAndCanBeConsumedOnlyOnce() {
        String token = issue();
        for (Object[] wrong : new Object[][]{{2L, 29L, "tenant/1/new.jpg", "new.jpg"},
                {1L, 30L, "tenant/1/new.jpg", "new.jpg"}, {1L, 29L, "tenant/1/old.jpg", "new.jpg"},
                {1L, 29L, "tenant/1/new.jpg", "renamed.jpg"}}) {
            assertThrows(RuntimeException.class, () -> service.consume(token, (Long) wrong[0], (Long) wrong[1],
                    (String) wrong[2], (String) wrong[3]));
        }
        assertEquals("https://trusted.test/new.jpg", service.consume(token, 1L, 29L, "tenant/1/new.jpg", "new.jpg").getUrl());
        assertThrows(RuntimeException.class, () -> service.consume(token, 1L, 29L, "tenant/1/new.jpg", "new.jpg"));
        verify(values).set(eq(FileUploadTicketService.key(token)), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test void missingExpiredAndMalformedTicketsNeverPermitRegistration() {
        String token = issue(); store.clear();
        assertThrows(RuntimeException.class, () -> service.consume(token, 1L, 29L, "tenant/1/new.jpg", "new.jpg"));
        assertThrows(RuntimeException.class, () -> service.consume(null, 1L, 29L, "tenant/1/new.jpg", "new.jpg"));
        assertThrows(RuntimeException.class, () -> service.consume("not-a-ticket", 1L, 29L, "tenant/1/new.jpg", "new.jpg"));
    }
}
