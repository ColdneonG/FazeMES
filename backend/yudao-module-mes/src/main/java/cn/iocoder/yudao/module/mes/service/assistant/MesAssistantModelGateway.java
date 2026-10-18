package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only administrators configure destinations. Requests pin validated DNS results and never follow redirects. */
@Service
public class MesAssistantModelGateway {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MesAssistantSecretStore secrets;
    public record Settings(String provider, String baseUrl, String model, String apiKey, boolean clearApiKey,
                           double temperature, int maxTokens, boolean enabled, Integer contextTokens) {
        public Settings(String provider, String baseUrl, String model, String apiKey, boolean clearApiKey,
                        double temperature, int maxTokens, boolean enabled) {
            this(provider, baseUrl, model, apiKey, clearApiKey, temperature, maxTokens, enabled, 8192);
        }
        @Override public String toString() { return "Settings[provider=" + provider + ", model=" + model + ", apiKey=<redacted>]"; }
    }
    private record Config(String provider, String baseUrl, String model, String ciphertext,
                          double temperature, int maxTokens, boolean enabled, int contextTokens) {}
    private record Target(URI uri, InetAddress address, int port) {}

    public MesAssistantModelGateway(JdbcTemplate jdbc, ObjectMapper json, MesAssistantSecretStore secrets) {
        this.jdbc = jdbc; this.json = json; this.secrets = secrets;
    }

    public Map<String, Object> status() {
        Config config = configuration();
        boolean configured = configured(config);
        return Map.of("configured", configured, "provider", config == null ? "none" : config.provider(),
                "model", config == null ? "" : config.model(), "retrievalMode", "lexical_bm25",
                "message", configured ? "模型已配置；可进行连接测试" : "模型未启用或未配置完整；知识检索仍可独立使用");
    }

    public Map<String, Object> settings() {
        Config config = configuration();
        if (config == null) return Map.of("provider", "openai", "baseUrl", "https://api.openai.com/v1", "model", "",
                "apiKeyConfigured", false, "temperature", .2, "maxTokens", 1024, "enabled", false, "contextTokens", 8192);
        return Map.of("provider", config.provider(), "baseUrl", config.baseUrl(), "model", config.model(),
                "apiKeyConfigured", config.ciphertext() != null && !config.ciphertext().isBlank(),
                "temperature", config.temperature(), "maxTokens", config.maxTokens(), "enabled", config.enabled(), "contextTokens", config.contextTokens());
    }

    public Map<String, Object> save(Settings settings, long userId) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        if (settings == null || settings.provider() == null || !Set.of("openai", "ollama").contains(settings.provider())) throw MesAssistantErrors.invalid("仅支持OpenAI兼容或Ollama接口");
        String model = settings.model() == null ? "" : settings.model().strip();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}")) throw MesAssistantErrors.invalid("模型名称无效");
        if (!Double.isFinite(settings.temperature()) || settings.temperature() < 0 || settings.temperature() > 1.5
                || settings.maxTokens() < 128 || settings.maxTokens() > 4096) throw MesAssistantErrors.invalid("温度需在0至1.5，输出长度需在128至4096");
        int contextTokens = settings.contextTokens() == null ? 8192 : settings.contextTokens();
        if (contextTokens < 2048 || contextTokens > 131072 || settings.maxTokens() > contextTokens - 512)
            throw MesAssistantErrors.invalid("上下文需在2048至131072，且至少比最大输出长度多512");
        String baseUrl = settings.baseUrl() == null ? "" : settings.baseUrl().strip().replaceAll("/+$", "");
        Target target = validateTarget(baseUrl);
        Config existing = configuration();
        String secret = settings.apiKey() == null ? "" : settings.apiKey().strip();
        if (secret.length() > 4096 || !secret.matches("[\\x21-\\x7E]*")) throw MesAssistantErrors.invalid("API Key格式无效");
        if (settings.clearApiKey() && !secret.isEmpty()) throw MesAssistantErrors.invalid("清除API Key时请勿同时输入新密钥");
        String ciphertext = settings.clearApiKey() ? null : existing == null ? null : existing.ciphertext();
        if (!secret.isEmpty()) ciphertext = secrets.encrypt(secret, tenant);
        else if (ciphertext != null && existing != null && !Objects.equals(URI.create(existing.baseUrl()).getHost(), target.uri().getHost()))
            throw MesAssistantErrors.invalid("服务主机变更时需重新输入或明确清除API Key");
        jdbc.update("""
                INSERT INTO mes_assistant_model_config(tenant_id,provider,base_url,model_name,secret_ciphertext,temperature,max_tokens,enabled,updater,context_tokens)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE provider=VALUES(provider),base_url=VALUES(base_url),
                model_name=VALUES(model_name),secret_ciphertext=VALUES(secret_ciphertext),temperature=VALUES(temperature),
                max_tokens=VALUES(max_tokens),enabled=VALUES(enabled),updater=VALUES(updater),context_tokens=VALUES(context_tokens),update_time=CURRENT_TIMESTAMP
                """, tenant, settings.provider(), baseUrl, model, ciphertext, settings.temperature(), settings.maxTokens(), settings.enabled(), userId, contextTokens);
        return settings();
    }

    public Map<String, Object> testConnection() {
        Config config = configuration();
        if (!configured(config)) throw MesAssistantErrors.model("请先保存并启用完整模型配置");
        complete(List.of(Map.of("role", "user", "content", "请仅回复OK，用于连接测试。")), 16);
        return Map.of("connected", true, "provider", config.provider(), "model", config.model(), "message", "连接成功，模型已返回真实响应");
    }

    public void reset() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        jdbc.update("DELETE FROM mes_assistant_model_config WHERE tenant_id=?", tenant);
    }

    public String complete(List<Map<String, String>> messages) { return complete(messages, null); }

    private String complete(List<Map<String, String>> messages, Integer outputLimit) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        Config config = configuration();
        if (!configured(config)) throw MesAssistantErrors.model("模型未配置或未启用");
        Target target = validateTarget(config.baseUrl());
        Map<String, Object> payload = new LinkedHashMap<>();
        int maxTokens = outputLimit == null ? config.maxTokens() : outputLimit;
        payload.put("model", config.model()); payload.put("messages", boundedMessages(messages, config.contextTokens() - maxTokens - 256)); payload.put("stream", false);
        if (config.provider().equals("ollama")) payload.put("options", Map.of("temperature", config.temperature(), "num_predict", maxTokens, "num_ctx", config.contextTokens()));
        else { payload.put("temperature", config.temperature()); payload.put("max_tokens", maxTokens); }
        String apiKey = secrets.decrypt(config.ciphertext(), tenant);
        try {
            String path = target.uri().getRawPath() + (config.provider().equals("ollama") ? "/api/chat" : "/chat/completions");
            byte[] response = post(target, path, json.writeValueAsBytes(payload), apiKey);
            var data = json.readTree(response);
            String content = config.provider().equals("ollama") ? data.path("message").path("content").asText("")
                    : data.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank() || content.length() > 100_000) throw MesAssistantErrors.model("模型未返回有效文本");
            return content.strip();
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException exception) { throw exception; }
        catch (Exception exception) { throw MesAssistantErrors.model("模型连接失败或响应格式不兼容，请检查管理员配置和服务状态"); }
    }

    private Config configuration() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        return jdbc.query("SELECT provider,base_url,model_name,secret_ciphertext,temperature,max_tokens,enabled,context_tokens FROM mes_assistant_model_config WHERE tenant_id=?",
                (rs, index) -> new Config(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getInt(6), rs.getBoolean(7), rs.getInt(8)), tenant).stream().findFirst().orElse(null);
    }

    int inputBudget() {
        Config config = configuration();
        return config == null ? 0 : config.contextTokens() - config.maxTokens() - 256;
    }

    /** UTF-8 bytes conservatively upper-bound byte-level tokenizer tokens; overhead covers message framing. */
    static int tokenUpperBound(List<Map<String, String>> messages) {
        long bytes = 0;
        for (var message : messages) bytes += message.getOrDefault("content", "").getBytes(StandardCharsets.UTF_8).length + 128L;
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    static List<Map<String, String>> boundedMessages(List<Map<String, String>> messages, int budget) {
        List<Map<String, String>> bounded = new ArrayList<>(messages);
        int keepFirst = !bounded.isEmpty() && bounded.get(0).get("role").equals("system") ? 1 : 0;
        while (tokenUpperBound(bounded) > budget && bounded.size() > keepFirst + 1) {
            bounded.remove(keepFirst);
            // Old exchanges are removed as a unit; never leave their assistant reply without its user question.
            while (bounded.size() > keepFirst + 1 && bounded.get(keepFirst).get("role").equals("assistant")) bounded.remove(keepFirst);
        }
        if (bounded.isEmpty() || tokenUpperBound(bounded) > budget)
            throw MesAssistantErrors.invalid("问题与必要资料超出模型上下文预算，请缩短问题、缩小知识范围或调整模型上下文/输出长度");
        return List.copyOf(bounded);
    }

    private static boolean configured(Config config) {
        return config != null && config.enabled() && !config.model().isBlank()
                && (config.provider().equals("ollama") || config.ciphertext() != null && !config.ciphertext().isBlank());
    }

    static Target validateTarget(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (baseUrl.length() > 500 || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !Set.of("http", "https").contains(uri.getScheme()) || !uri.getRawPath().matches("[A-Za-z0-9/_.~\\-]*")) throw new IllegalArgumentException();
            boolean local = host.equals("127.0.0.1");
            if (!local && !uri.getScheme().equals("https")) throw new IllegalArgumentException();
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved.length == 0) throw new IllegalArgumentException();
            for (InetAddress address : resolved) {
                byte[] bytes = address.getAddress();
                boolean reservedV4 = bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127
                        || (bytes[0] & 255) >= 224 || (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19));
                boolean privateV6 = bytes.length == 16 && (bytes[0] & 254) == 252;
                if (!local && (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || reservedV4 || privateV6)) throw new IllegalArgumentException();
            }
            int port = uri.getPort() == -1 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return new Target(uri, resolved[0], port);
        } catch (Exception exception) { throw MesAssistantErrors.invalid("模型地址无效：仅允许HTTPS公共服务或127.0.0.1本地服务，禁止私网、metadata和URL凭据/查询参数"); }
    }

    private static byte[] post(Target target, String path, byte[] body, String apiKey) throws Exception {
        Socket socket = new Socket();
        try {
            // Connect to the address that passed validation; DNS is never resolved again by the HTTP transport.
            socket.connect(new InetSocketAddress(target.address(), target.port()), 10_000);
            socket.setSoTimeout(60_000);
            if (target.uri().getScheme().equals("https")) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, target.uri().getHost(), target.port(), true);
                var parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!target.uri().getHost().matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(target.uri().getHost())));
                tls.setSSLParameters(parameters); tls.setSoTimeout(60_000); tls.startHandshake(); socket = tls;
            }
            String authorization = apiKey.isEmpty() ? "" : "Authorization: Bearer " + apiKey + "\r\n";
            String headers = "POST " + (path.isEmpty() ? "/" : path) + " HTTP/1.1\r\nHost: " + target.uri().getHost() + ":" + target.port()
                    + "\r\nContent-Type: application/json\r\nAccept: application/json\r\nConnection: close\r\n" + authorization
                    + "Content-Length: " + body.length + "\r\n\r\n";
            OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String status = line(input); if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException();
            int code = Integer.parseInt(status.substring(9, 12));
            Map<String, String> responseHeaders = new HashMap<>(); int headerSize = status.length();
            for (String value = line(input); !value.isEmpty(); value = line(input)) {
                headerSize += value.length(); if (headerSize > 32768) throw new IOException();
                int colon = value.indexOf(':'); if (colon <= 0) throw new IOException();
                responseHeaders.put(value.substring(0, colon).toLowerCase(Locale.ROOT), value.substring(colon + 1).strip());
            }
            if (code != 200) throw MesAssistantErrors.model("模型服务返回HTTP " + code + "，请检查连接配置");
            if (responseHeaders.containsKey("content-encoding") && !responseHeaders.get("content-encoding").equalsIgnoreCase("identity")) throw new IOException();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            if ("chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"))) {
                while (true) {
                    String lengthLine = line(input).split(";", 2)[0]; long count = Long.parseLong(lengthLine, 16);
                    if (count == 0) break;
                    if (count < 0 || count > MAX_RESPONSE_BYTES - response.size()) throw new IOException();
                    copy(input, response, (int) count); if (!line(input).isEmpty()) throw new IOException();
                }
            } else if (responseHeaders.containsKey("content-length")) {
                long count = Long.parseLong(responseHeaders.get("content-length"));
                if (count < 0 || count > MAX_RESPONSE_BYTES) throw new IOException(); copy(input, response, (int) count);
            } else {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > MAX_RESPONSE_BYTES) throw new IOException(); response.write(buffer, 0, count);
                }
            }
            return response.toByteArray();
        } finally { socket.close(); }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return result.toString(StandardCharsets.US_ASCII).replaceFirst("\\r$", "");
            if (result.size() >= 8192) throw new IOException(); result.write(value);
        }
        throw new EOFException();
    }

    private static void copy(InputStream input, OutputStream output, int count) throws IOException {
        byte[] buffer = new byte[8192]; int left = count;
        while (left > 0) { int received = input.read(buffer, 0, Math.min(left, buffer.length)); if (received < 0) throw new EOFException(); output.write(buffer, 0, received); left -= received; }
    }
}
