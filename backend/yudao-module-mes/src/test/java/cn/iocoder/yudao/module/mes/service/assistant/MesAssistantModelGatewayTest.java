package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MesAssistantModelGatewayTest extends MesAssistantTestDatabase {
    @Test void administratorResetDeletesOnlyTheCurrentTenantAndRestoresTheUnconfiguredGetContract() {
        var pristine = models.settings();
        models.save(settings("http://127.0.0.1:11434", "tenant-one-test-only-key", false), 10);
        login(2L);
        models.save(settings("http://127.0.0.1:11435", "tenant-two-test-only-key", false), 20);
        var otherTenant = models.settings();
        login(1L);
        models.reset();
        assertEquals(pristine, models.settings());
        assertEquals(false, models.status().get("configured"));
        assertEquals(false, models.settings().get("apiKeyConfigured"));
        assertEquals("", models.settings().get("model"));
        login(2L);
        assertEquals(otherTenant, models.settings());
        assertEquals(true, models.status().get("configured"));
        TenantContextHolder.clear(); assertThrows(NullPointerException.class, models::reset);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_model_config", Integer.class));
    }

    @Test void encryptedCredentialsAreTenantBoundRedactedAndPreservedOnlyWhenRequested() throws Exception {
        var settings = settings("http://127.0.0.1:11434", "test-only-not-a-real-key", false);
        var saved = models.save(settings, 10);
        assertEquals(true, saved.get("apiKeyConfigured")); assertFalse(saved.containsKey("apiKey"));
        String ciphertext = jdbc.queryForObject("SELECT secret_ciphertext FROM mes_assistant_model_config WHERE tenant_id=1", String.class);
        assertNotNull(ciphertext); assertFalse(ciphertext.contains("test-only-not-a-real-key"));
        assertEquals("test-only-not-a-real-key", secrets.decrypt(ciphertext, 1));
        assertFalse(settings.toString().contains("test-only-not-a-real-key"));
        var reloadedSecrets = new MesAssistantSecretStore(new org.springframework.mock.env.MockEnvironment()
                .withProperty("mes.assistant.master-key-file", temporary.resolve("private/master.key").toString()));
        assertEquals("test-only-not-a-real-key", reloadedSecrets.decrypt(ciphertext, 1));
        assertThrows(ServiceException.class, () -> secrets.decrypt(ciphertext, 2));
        assertTrue(Files.exists(temporary.resolve("private/master.key")));
        assertFalse(models.settings().toString().contains("test-only-not-a-real-key"));
        models.save(settings("http://127.0.0.1:11434", "", false), 10);
        assertEquals(ciphertext, jdbc.queryForObject("SELECT secret_ciphertext FROM mes_assistant_model_config WHERE tenant_id=1", String.class));
        assertEquals(true, models.status().get("configured"));
        login(2L);
        assertEquals(false, models.status().get("configured")); assertEquals(false, models.settings().get("apiKeyConfigured"));
        login(1L);
        models.save(settings("http://127.0.0.1:11434", "", true), 10);
        assertEquals(false, models.settings().get("apiKeyConfigured"));
        assertEquals(false, models.status().get("configured"));
    }

    @Test void rejectsSsrfTargetsUrlCredentialsAndInvalidModelLimitsWithoutOpeningConnections() {
        for (String url : List.of("http://example.com/v1", "https://169.254.169.254", "https://10.1.2.3/v1", "https://172.16.0.1", "https://192.168.0.1",
                "https://100.64.0.1", "https://[fc00::1]", "https://[::1]", "http://localhost:11434", "http://127.0.0.1@169.254.169.254", "https://127.0.0.1/v1?key=bad", "http://127.0.0.1:11434/#fragment")) {
            assertThrows(ServiceException.class, () -> models.save(settings(url, "", false), 10), url);
        }
        assertThrows(ServiceException.class, () -> models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1", "model", "", false, Double.NaN, 1024, true), 10));
        assertThrows(ServiceException.class, () -> models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1", "model", "", false, .2, 4097, true), 10));
        assertThrows(ServiceException.class, () -> models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1", "bad\nmodel", "", false, .2, 1024, true), 10));
        assertThrows(ServiceException.class, () -> models.save(settings("http://127.0.0.1", "test\r\nInjected: bad", false), 10));
        assertThrows(ServiceException.class, () -> models.save(settings("http://127.0.0.1", "test-key", true), 10));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_model_config", Integer.class));
    }

    @Test void realOpenAiCompatibleHttpResponseIsPersistedWithActualRetrievalCitations() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> prompt = new AtomicReference<>(); AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            prompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"根据测试文档，点检异常必须停机。[1]\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            models.save(settings(base, "test-only-key", false), 10);
            long knowledge = service.saveBase(null, "点检", "", 10);
            long document = ((Number) service.upload(knowledge, "点检依据", "点检.txt", "设备点检异常必须停机。".getBytes(StandardCharsets.UTF_8), 10).get("id")).longValue();
            service.publish(document, true); long session = service.createSession(null, 10);
            var result = service.send(session, "设备点检异常如何处理？", List.of(knowledge), 10);
            assertEquals("ANSWERED", result.get("status")); assertEquals("根据测试文档，点检异常必须停机。[1]", result.get("answer"));
            assertTrue(prompt.get().contains("设备点检异常必须停机")); assertFalse(prompt.get().contains("test-only-key"));
            assertEquals("Bearer test-only-key", authorization.get());
            assertEquals(2, ((List<?>) service.session(session, 10).get("messages")).size());
            assertEquals(1, ((List<?>) result.get("citations")).size());
            assertEquals(true, models.testConnection().get("connected"));
            assertFalse(models.settings().toString().contains("test-only-key"));
        } finally { server.stop(0); }
    }

    @Test void ollamaUsesItsRealProtocolWithoutRequiringAnApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"message\":{\"content\":\"ollama test response\"},\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            models.save(new MesAssistantModelGateway.Settings("ollama", "http://127.0.0.1:" + server.getAddress().getPort(), "local-test:1", "", false, .2, 1024, true), 10);
            assertEquals("ollama test response", models.complete(List.of(Map.of("role", "user", "content", "ping"))));
            var payload = json.readTree(request.get()); assertEquals(false, payload.get("stream").asBoolean());
            assertEquals(1024, payload.get("options").get("num_predict").asInt()); assertEquals("local-test:1", payload.get("model").asText());
            assertEquals(8192, payload.get("options").get("num_ctx").asInt());
        } finally { server.stop(0); }
    }

    @Test void contextBudgetDropsOldExchangesTogetherAndRejectsAnOversizedCurrentQuestionBeforeHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> captured = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"budget test response\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", "test-only-key", false, .2, 128, true, 2048), 10);
            models.complete(List.of(Map.of("role", "system", "content", "preserved rules"),
                    Map.of("role", "user", "content", "old-question-" + "x".repeat(900)),
                    Map.of("role", "assistant", "content", "old-answer-" + "x".repeat(900)),
                    Map.of("role", "user", "content", "recent question"), Map.of("role", "assistant", "content", "recent answer"),
                    Map.of("role", "user", "content", "current question")));
            var sent = json.readTree(captured.get()).get("messages");
            assertEquals(4, sent.size()); assertEquals("preserved rules", sent.get(0).get("content").asText());
            assertEquals("recent question", sent.get(1).get("content").asText());
            assertEquals("recent answer", sent.get(2).get("content").asText());
            assertEquals("current question", sent.get(3).get("content").asText());
            assertFalse(captured.get().contains("old-question")); assertFalse(captured.get().contains("old-answer"));
            assertThrows(ServiceException.class, () -> models.complete(List.of(Map.of("role", "system", "content", "rules"), Map.of("role", "user", "content", "中".repeat(1000)))));
            assertEquals(1, calls.get());
            assertThrows(ServiceException.class, () -> models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1", "test", "", false, .2, 1024, true, 1024), 10));
        } finally { server.stop(0); }
    }

    @Test void onlyCitationsActuallyFittingTheModelContextAreNumberedReturnedAndPersisted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"budgeted sources [1]\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            models.save(new MesAssistantModelGateway.Settings("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", "test-only-key", false, .2, 128, true, 3072), 10);
            long base = service.saveBase(null, "Inspection manuals", "", 10);
            for (int index = 1; index <= 5; index++) {
                long id = ((Number) service.upload(base, "manual" + index, "manual.txt", ("inspection " + "valid ".repeat(140)).getBytes(StandardCharsets.UTF_8), 10).get("id")).longValue();
                service.publish(id, true);
            }
            long session = service.createSession(null, 10);
            var response = service.send(session, "inspection", List.of(base), 10);
            assertEquals("ANSWERED", response.get("status"));
            @SuppressWarnings("unchecked") var citations = (List<Map<String, Object>>) response.get("citations");
            assertFalse(citations.isEmpty()); assertTrue(citations.size() < 5);
            String system = json.readTree(captured.get()).get("messages").get(0).get("content").asText();
            for (int index = 0; index < citations.size(); index++) {
                assertTrue(system.contains("[" + (index + 1) + "] " + citations.get(index).get("title")));
                assertTrue(system.contains(citations.get(index).get("content").toString()));
            }
            assertFalse(system.contains("[" + (citations.size() + 1) + "]"));
            @SuppressWarnings("unchecked") var messages = (List<Map<String, Object>>) service.session(session, 10).get("messages");
            assertEquals(citations.size(), ((List<?>) messages.get(1).get("citations")).size());
        } finally { server.stop(0); }
    }

    @Test void redirectsAndProviderErrorsNeverLeakSecretsOrCreateInventedAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/redirect/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/error/chat/completions", exchange -> {
            byte[] body = "provider-private-body test-only-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            String local = "http://127.0.0.1:" + server.getAddress().getPort();
            models.save(settings(local + "/redirect", "test-only-key", false), 10);
            ServiceException redirect = assertThrows(ServiceException.class, models::testConnection); assertTrue(redirect.getMessage().contains("302")); assertEquals(1, calls.get());
            models.save(settings(local + "/error", "", false), 10);
            long session = service.createSession(null, 10);
            var response = service.send(session, "点检", List.of(), 10);
            assertEquals("MODEL_ERROR", response.get("status")); assertNull(response.get("answer"));
            assertFalse(response.toString().contains("test-only-key")); assertFalse(response.toString().contains("provider-private-body"));
            assertEquals(1, ((List<?>) service.session(session, 10).get("messages")).size());
        } finally { server.stop(0); }
    }

    private static void login(long tenant) { TenantContextHolder.setTenantId(tenant); cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(10L).setTenantId(tenant).setUserType(2),new org.springframework.mock.web.MockHttpServletRequest()); }
    private static MesAssistantModelGateway.Settings settings(String url, String key, boolean clear) {
        return new MesAssistantModelGateway.Settings("openai", url, "test-model", key, clear, .2, 1024, true);
    }
}
