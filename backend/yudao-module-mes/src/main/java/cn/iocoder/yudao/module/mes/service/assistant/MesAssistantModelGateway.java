package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.iocoder.yudao.module.mes.service.assistant.MesModelUsageService.Purpose;

/** Only administrators configure destinations. Requests pin validated DNS results and never follow redirects. */
@Service
public class MesAssistantModelGateway {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MesAssistantSecretStore secrets;
    private final MesModelUsageService usage;
    private final String jevBaseUrl;
    private final int totalDeadlineMs;
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"mes-model-deadline");thread.setDaemon(true);return thread;});
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
        this(jdbc,json,secrets,new MesModelUsageService(jdbc,json,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))));
    }
    @Autowired public MesAssistantModelGateway(JdbcTemplate jdbc,ObjectMapper json,MesAssistantSecretStore secrets,MesModelUsageService usage){this(jdbc,json,secrets,usage,"https://api.typesafe.ai/v1",75_000);}
    MesAssistantModelGateway(JdbcTemplate jdbc,ObjectMapper json,MesAssistantSecretStore secrets,MesModelUsageService usage,String jevBaseUrl,int totalDeadlineMs){this.jdbc=jdbc;this.json=json;this.secrets=secrets;this.usage=Objects.requireNonNull(usage);this.jevBaseUrl=Objects.requireNonNull(jevBaseUrl);if(totalDeadlineMs<1||totalDeadlineMs>75_000)throw new IllegalArgumentException("Invalid transport deadline");this.totalDeadlineMs=totalDeadlineMs;}

    public Map<String, Object> status() {
        Config config = configuration();
        boolean configured = configured(config);
        boolean budgetReady=usage.budgetReady();
        return Map.of("configured", configured, "provider", config == null ? "none" : config.provider(),
                "model", config == null ? "" : config.model(), "retrievalMode", "lexical_bm25","budgetReady",budgetReady,
                "blockReason",budgetReady?"":"模型预算尚未配置或未启用，知识检索仍可使用",
                "message", configured ? "模型凭据已配置；连接测试和答复均计入共享配额" : "模型未启用或未配置完整；知识检索仍可独立使用");
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
        complete(Purpose.MODEL_CONNECTION_TEST,List.of(Map.of("role", "user", "content", "请仅回复OK，用于连接测试。")), 16);
        return Map.of("connected", true, "provider", config.provider(), "model", config.model(), "message", "连接成功，模型已返回真实响应");
    }

    public void reset() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        jdbc.update("DELETE FROM mes_assistant_model_config WHERE tenant_id=?", tenant);
    }

    public String complete(List<Map<String, String>> messages) { return complete(Purpose.KNOWLEDGE_CHAT,messages,null); }
    public String complete(Purpose purpose,List<Map<String,String>> messages){if(purpose==null||!Set.of(Purpose.KNOWLEDGE_CHAT,Purpose.BUSINESS_INSIGHT).contains(purpose))throw MesAssistantErrors.invalid("模型用途无效");return complete(purpose,messages,null);}
    private String complete(Purpose purpose,List<Map<String, String>> messages, Integer outputLimit) {
        noTransaction();
        long tenant = TenantContextHolder.getRequiredTenantId();
        Config config = configuration();
        if (!configured(config)) throw MesAssistantErrors.model("模型未配置或未启用");
        Target target = validateTarget(config.baseUrl());
        Map<String, Object> payload = new LinkedHashMap<>();
        int maxTokens = outputLimit == null ? config.maxTokens() : outputLimit;
        List<Map<String,String>> bounded=boundedMessages(messages,config.contextTokens()-maxTokens-256);
        payload.put("model", config.model()); payload.put("messages", bounded); payload.put("stream", false);
        if (config.provider().equals("ollama")) payload.put("options", Map.of("temperature", config.temperature(), "num_predict", maxTokens, "num_ctx", config.contextTokens()));
        else { payload.put("temperature", config.temperature()); payload.put("max_tokens", maxTokens); }
        String apiKey = secrets.decrypt(config.ciphertext(), tenant);
        try {
            String path = target.uri().getRawPath() + (config.provider().equals("ollama") ? "/api/chat" : "/chat/completions");
            byte[] response = invoke(purpose,"CHAT",config.provider(),config.model(),tokenUpperBound(bounded),maxTokens,target,path,json.writeValueAsBytes(payload),apiKey);
            var data = json.readTree(response);
            String content = config.provider().equals("ollama") ? data.path("message").path("content").asText("")
                    : data.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank() || content.length() > 100_000) throw MesAssistantErrors.model("模型未返回有效文本");
            return content.strip();
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException exception) { throw exception; }
        catch (Exception exception) { throw MesAssistantErrors.model("模型连接失败或响应格式不兼容，请检查管理员配置和服务状态"); }
    }

    /** Fixed server configuration; callers cannot submit a URL, key or price to the transport. */
    public byte[] evaluateJev(Purpose purpose,byte[] body){
        noTransaction();if(purpose==null||!Set.of(Purpose.JEV_TRIAGE,Purpose.JEV_CONNECTION_TEST).contains(purpose)||body==null||body.length>128*1024)throw MesAssistantErrors.invalid("Jev调用参数无效");long tenant=TenantContextHolder.getRequiredTenantId();
        var configs=jdbc.queryForList("SELECT model_name,secret_ciphertext,enabled FROM mes_insight_jev_config WHERE tenant_id=?",tenant);if(configs.isEmpty()||!Boolean.TRUE.equals(configs.get(0).get("enabled")))throw MesAssistantErrors.model("Jev尚未配置或启用");var config=configs.get(0);String model=Objects.toString(config.get("model_name"),"");
        try{var parsed=json.readTree(body);if(!parsed.isObject()||!model.equals(parsed.path("model").asText()))throw MesAssistantErrors.invalid("Jev模型配置已变更，请重试");String key=secrets.decrypt(Objects.toString(config.get("secret_ciphertext"),null),tenant);if(key.isBlank())throw MesAssistantErrors.model("Jev密钥未配置");Target target=validateTarget(jevBaseUrl);return invoke(purpose,"JEV","typesafe",model,0,0,target,target.uri().getRawPath()+"/systemone",body,key);}
        catch(cn.iocoder.yudao.framework.common.exception.ServiceException ex){throw ex;}catch(Exception ex){throw MesAssistantErrors.model("Jev连接失败或响应无效，请检查配置；可继续手动建立异常");}
    }
    private static void noTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw MesAssistantErrors.model("模型调用前必须完成业务事务，请刷新后重试或联系管理员");}
    private record Usage(Long input,Long output,Long total) { }
    private Usage reported(JsonNode data,String provider){try{JsonNode in="ollama".equals(provider)?data.get("prompt_eval_count"):data.path("usage").get("prompt_tokens"),out="ollama".equals(provider)?data.get("eval_count"):data.path("usage").get("completion_tokens");if(in==null||out==null||!in.isIntegralNumber()||!out.isIntegralNumber()||!in.canConvertToLong()||!out.canConvertToLong()||in.longValue()<0||out.longValue()<0)return new Usage(null,null,null);long total=Math.addExact(in.longValue(),out.longValue());if(!"ollama".equals(provider)){JsonNode t=data.path("usage").get("total_tokens");if(t==null||!t.isIntegralNumber()||!t.canConvertToLong()||t.longValue()!=total)return new Usage(null,null,null);}return new Usage(in.longValue(),out.longValue(),total);}catch(Exception ex){return new Usage(null,null,null);}}
    private byte[] invoke(Purpose purpose,String channel,String provider,String model,int inputUpper,int outputLimit,Target target,String path,byte[] body,String key){
        var reservation=usage.reserve(purpose,channel,provider,model,inputUpper,outputLimit);long start=System.nanoTime();
        try{usage.dispatched(reservation.id());Response response=post(target,path,body,key,totalDeadlineMs);JsonNode parsed=null;try{parsed=json.readTree(response.body);}catch(Exception ignored){ }Usage actual=parsed==null?new Usage(null,null,null):reported(parsed,provider);String served=parsed==null||!parsed.path("model").isTextual()?null:parsed.path("model").textValue();boolean validText=parsed!=null&&("JEV".equals(channel)?validJev(body,parsed):validContent(parsed,provider));String outcome=response.status!=200?"HTTP_ERROR":validText?"SUCCESS":"INVALID_RESPONSE";usage.settle(reservation.id(),new MesModelUsageService.Settlement(outcome,actual.input,actual.output,actual.total,served,response.status,elapsed(start),true,false));if(response.status!=200)throw MesAssistantErrors.model("模型服务返回HTTP "+response.status+"，请检查连接配置");if(!validText)throw MesAssistantErrors.model("模型未返回有效文本或响应格式不兼容");return response.body;}
        catch(TransportFailure failure){usage.settle(reservation.id(),new MesModelUsageService.Settlement("NETWORK_ERROR",null,null,null,null,failure.status,elapsed(start),failure.status!=null,!failure.maybeSent));throw MesAssistantErrors.model("模型连接失败或响应格式不兼容，请检查管理员配置和服务状态");}
    }
    private boolean validJev(byte[] request,JsonNode response){try{cn.iocoder.yudao.module.mes.service.insight.MesJevProtocol.validate(json.readTree(request),response);return true;}catch(Exception ex){return false;}}
    private static boolean validContent(JsonNode data,String provider){JsonNode content="ollama".equals(provider)?data.path("message").path("content"):data.path("choices").path(0).path("message").path("content");return content.isTextual()&&!content.textValue().isBlank()&&content.textValue().length()<=100_000;}
    private static long elapsed(long start){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}

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

    private record Response(int status,byte[] body) { }
    private static final class TransportFailure extends RuntimeException {
        final boolean maybeSent;final Integer status;
        TransportFailure(boolean maybeSent,Integer status){super("Bounded model transport failed");this.maybeSent=maybeSent;this.status=status;}
    }
    /** Private transport has one metered caller. A total deadline also bounds slow/chunked providers. */
    private static Response post(Target target, String path, byte[] body, String apiKey,int totalDeadlineMs) {
        Socket socket = new Socket();boolean maybeSent=false;Integer knownStatus=null;
        AtomicReference<Socket> current=new AtomicReference<>(socket);
        ScheduledFuture<?> deadline=DEADLINES.schedule(()->{try{current.get().close();}catch(IOException ignored){ }},totalDeadlineMs,TimeUnit.MILLISECONDS);
        try {
            // Connect to the address that passed validation; DNS is never resolved again by the HTTP transport.
            socket.connect(new InetSocketAddress(target.address(), target.port()), Math.min(10_000,totalDeadlineMs));
            socket.setSoTimeout(Math.min(60_000,totalDeadlineMs));
            if (target.uri().getScheme().equals("https")) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, target.uri().getHost(), target.port(), true);
                var parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!target.uri().getHost().matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(target.uri().getHost())));
                tls.setSSLParameters(parameters); tls.setSoTimeout(Math.min(60_000,totalDeadlineMs));current.set(tls);tls.startHandshake(); socket = tls;
            }
            String authorization = apiKey.isEmpty() ? "" : "Authorization: Bearer " + apiKey + "\r\n";
            String headers = "POST " + (path.isEmpty() ? "/" : path) + " HTTP/1.1\r\nHost: " + target.uri().getHost() + ":" + target.port()
                    + "\r\nContent-Type: application/json\r\nAccept: application/json\r\nConnection: close\r\n" + authorization
                    + "Content-Length: " + body.length + "\r\n\r\n";
            OutputStream output = socket.getOutputStream();maybeSent=true; output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String status = line(input); if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException();
            int code = Integer.parseInt(status.substring(9, 12));
            knownStatus=code;
            Map<String, String> responseHeaders = new HashMap<>(); int headerSize = status.length();
            for (String value = line(input); !value.isEmpty(); value = line(input)) {
                headerSize += value.length(); if (headerSize > 32768) throw new IOException();
                int colon = value.indexOf(':'); if (colon <= 0) throw new IOException();
                responseHeaders.put(value.substring(0, colon).toLowerCase(Locale.ROOT), value.substring(colon + 1).strip());
            }
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
            return new Response(code,response.toByteArray());
        }catch(Exception exception){throw new TransportFailure(maybeSent,knownStatus);}
        finally {deadline.cancel(false);try{current.get().close();}catch(IOException ignored){ }try{socket.close();}catch(IOException ignored){ }}
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
