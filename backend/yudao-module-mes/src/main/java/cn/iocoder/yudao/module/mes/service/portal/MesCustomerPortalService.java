package cn.iocoder.yudao.module.mes.service.portal;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.report.MesReportTimeFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yunshu's catalog / checkout / order history adapted to genuine tenant and customer identities.
 * All JDBC joins are explicitly scoped, including anonymous requests where tenant interception is ignored.
 * Customer sessions never confer administrator privileges. Only SHA-256 token digests are persisted.
 */
@Service
public class MesCustomerPortalService {
    private static final String LOGIN_ERROR = "账号、密码或租户不正确，或账号暂不可用";
    private static final String SESSION_ERROR = "客户会话已失效，请重新登录";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    private final ConcurrentHashMap<String, AttemptWindow> loginWindows = new ConcurrentHashMap<>();
    private record AttemptWindow(long start, int attempts) {}
    private record Identity(long tenantId, long accountId, long clientId, String displayName, String clientName) {}

    public record LoginRequest(Long tenantId, String username, String password) {}
    public record AccountSave(Long id, Long clientId, String username, String displayName, String password,
                              Boolean enabled, LocalDateTime expireTime) {}
    public record CatalogSave(Long productId, String description, Boolean enabled) {}
    public record RequestLine(Long productId, BigDecimal quantity) {}
    public record OrderRequest(String requestKey, LocalDate deliveryDate, String remark, List<RequestLine> items) {}
    public record ReviewRequest(Long id, String status, String reviewNote) {}

    public MesCustomerPortalService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static ServiceException bad(String message) { return new ServiceException(400, message); }
    private static ServiceException unauthorized(String message) { return new ServiceException(401, message); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static boolean flag(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
    private static LocalDateTime time(Object value) {
        return value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
    }
    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var md = rs.getMetaData();
            for (int column = 1; column <= md.getColumnCount(); column++) {
                String label = md.getColumnLabel(column).toLowerCase(Locale.ROOT);
                StringBuilder key = new StringBuilder();
                boolean upper = false;
                for (char c : label.toCharArray()) {
                    if (c == '_') upper = true;
                    else { key.append(upper ? Character.toUpperCase(c) : c); upper = false; }
                }
                Object value = rs.getObject(column);
                row.put(key.toString(), value instanceof Timestamp ts ? ts.toLocalDateTime() :
                        value instanceof java.sql.Date date ? date.toLocalDate() : value);
            }
            return row;
        }, args);
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private boolean validTenant(long tenant) {
        return !rows("SELECT id FROM system_tenant WHERE id=? AND deleted=0 AND status=0 AND expire_time>?", tenant, LocalDateTime.now()).isEmpty();
    }
    private void limitLogin(long tenant, String remoteAddress) {
        long now = System.currentTimeMillis();
        loginWindows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 60_000);
        if (loginWindows.size() > 5000) throw new ServiceException(429, "登录请求较多，请稍后再试");
        String key = tenant + ":" + text(remoteAddress);
        var window = loginWindows.compute(key, (ignored, old) -> old == null || now - old.start() >= 60_000
                ? new AttemptWindow(now, 1) : new AttemptWindow(old.start(), old.attempts() + 1));
        if (window.attempts() > 30) throw new ServiceException(429, "登录尝试过于频繁，请一分钟后重试");
    }
    /** Deliberately not transactional: failed-login counters must survive the authentication error. */
    public Map<String, Object> login(LoginRequest body, String remoteAddress) {
        if (body == null || body.tenantId() == null || body.tenantId() < 1) throw unauthorized(LOGIN_ERROR);
        limitLogin(body.tenantId(), remoteAddress);
        String username = text(body.username()).toLowerCase(Locale.ROOT);
        String password = body.password() == null ? "" : body.password();
        if (!username.matches("[a-z0-9._@-]{3,64}") || password.length() > 128) throw unauthorized(LOGIN_ERROR);
        var accounts = rows("SELECT a.* FROM mes_portal_account a JOIN mes_md_client c ON c.id=a.client_id AND c.tenant_id=a.tenant_id AND c.deleted=0 AND c.status=0 WHERE a.tenant_id=? AND a.username=? AND a.deleted=0", body.tenantId(), username);
        Map<String, Object> account = accounts.isEmpty() ? null : accounts.get(0);
        boolean matched = passwords.matches(password, account == null ? dummyHash : String.valueOf(account.get("passwordHash")));
        LocalDateTime now = LocalDateTime.now();
        if (account == null || !flag(account.get("enabled")) || !validTenant(body.tenantId())
                || account.get("expireTime") != null && !time(account.get("expireTime")).isAfter(now)
                || account.get("lockedUntil") != null && time(account.get("lockedUntil")).isAfter(now)) throw unauthorized(LOGIN_ERROR);
        long accountId = number(account, "id");
        if (!matched) {
            // The comparison and increment occur in one SQL statement so concurrent failures cannot lose increments.
            jdbc.update("UPDATE mes_portal_account SET locked_until=CASE WHEN failed_attempts>=4 THEN ? ELSE locked_until END,failed_attempts=failed_attempts+1 WHERE id=? AND tenant_id=? AND deleted=0", now.plusMinutes(15), accountId, body.tenantId());
            throw unauthorized(LOGIN_ERROR);
        }
        jdbc.update("UPDATE mes_portal_account SET failed_attempts=0,locked_until=NULL WHERE id=? AND tenant_id=?", accountId, body.tenantId());
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires = now.plusHours(8);
        if (account.get("expireTime") != null && time(account.get("expireTime")).isBefore(expires)) expires = time(account.get("expireTime"));
        jdbc.update("DELETE FROM mes_portal_session WHERE tenant_id=? AND account_id=? AND (expires_at<? OR revoked=1)", body.tenantId(), accountId, now);
        jdbc.update("INSERT INTO mes_portal_session(tenant_id,account_id,token_hash,auth_version,expires_at,revoked) VALUES(?,?,?,?,?,0)", body.tenantId(), accountId, hash(token), account.get("authVersion"), expires);
        return Map.of("token", token, "expiresAt", expires.toString(), "account", me(token));
    }
    private Identity identity(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthorized(SESSION_ERROR);
        var matches = rows("SELECT a.tenant_id,a.id AS account_id,a.client_id,a.display_name,c.name AS client_name FROM mes_portal_session s JOIN mes_portal_account a ON a.id=s.account_id AND a.tenant_id=s.tenant_id AND a.deleted=0 AND a.enabled=1 AND a.auth_version=s.auth_version JOIN mes_md_client c ON c.id=a.client_id AND c.tenant_id=a.tenant_id AND c.deleted=0 AND c.status=0 JOIN system_tenant te ON te.id=a.tenant_id AND te.deleted=0 AND te.status=0 AND te.expire_time>? WHERE s.token_hash=? AND s.revoked=0 AND s.expires_at>? AND (a.expire_time IS NULL OR a.expire_time>?)", LocalDateTime.now(), hash(token), LocalDateTime.now(), LocalDateTime.now());
        if (matches.isEmpty()) throw unauthorized(SESSION_ERROR);
        var row = matches.get(0);
        return new Identity(number(row,"tenantId"),number(row,"accountId"),number(row,"clientId"),String.valueOf(row.get("displayName")),String.valueOf(row.get("clientName")));
    }
    public Map<String, Object> me(String token) {
        Identity id = identity(token);
        return Map.of("displayName", id.displayName(), "clientName", id.clientName());
    }
    public void logout(String token) {
        Identity id = identity(token);
        jdbc.update("UPDATE mes_portal_session SET revoked=1 WHERE token_hash=? AND tenant_id=? AND account_id=?", hash(token), id.tenantId(), id.accountId());
    }

    // Whitelist only the public reference sale price; never join/select purchase or cost fields.
    private static final String CATALOG_SELECT = "SELECT p.product_id,i.code AS product_code,i.name AS product_name,i.specification,COALESCE(u.name,'') AS unit_name,p.description,(SELECT cp.sale_price FROM mes_cost_profile cp WHERE cp.tenant_id=p.tenant_id AND cp.item_id=p.product_id AND cp.deleted=0 ORDER BY cp.version DESC LIMIT 1) AS reference_sale_price FROM mes_portal_catalog p JOIN mes_md_item i ON i.id=p.product_id AND i.tenant_id=p.tenant_id AND i.deleted=0 AND i.status=0 LEFT JOIN mes_md_unit_measure u ON u.id=i.unit_measure_id AND u.tenant_id=i.tenant_id AND u.deleted=0";
    public List<Map<String, Object>> catalog(String token) {
        Identity id = identity(token);
        var catalog=rows(CATALOG_SELECT + " WHERE p.tenant_id=? AND p.enabled=1 ORDER BY i.code LIMIT 500", id.tenantId());
        for(var product:catalog){Object price=product.get("referenceSalePrice");product.put("referenceSalePrice",price==null?null:((BigDecimal)price).toPlainString());product.put("currency","CNY");product.put("taxBasis","EXCLUSIVE");}
        return catalog;
    }
    /** Customer-safe projection: no cost, supplier, internal notes, station operators or other clients. */
    public List<Map<String, Object>> orders(String token) {
        Identity id = identity(token);
        return rows("SELECT w.id,w.code,w.name,COALESCE(i.name,'') AS product_name,COALESCE(i.specification,'') AS specification,w.quantity,w.quantity_produced,w.request_date,w.finish_date,w.status FROM mes_pro_work_order w LEFT JOIN mes_md_item i ON i.id=w.product_id AND i.tenant_id=w.tenant_id AND i.deleted=0 WHERE w.tenant_id=? AND w.client_id=? AND w.deleted=0 AND w.status IN(1,2,3) ORDER BY w.id DESC LIMIT 200", id.tenantId(), id.clientId()).stream().map(MesReportTimeFormat::isoResponse).toList();
    }
    public List<Map<String, Object>> requests(String token) {
        Identity id = identity(token);
        var requests = rows("SELECT id,code,delivery_date,remark,status,review_note,review_time,create_time FROM mes_portal_request WHERE tenant_id=? AND client_id=? ORDER BY id DESC LIMIT 200", id.tenantId(), id.clientId());
        attachLines(requests, id.tenantId());
        return requests.stream().map(MesReportTimeFormat::isoResponse).toList();
    }
    private void attachLines(List<Map<String, Object>> requests, long tenant) {
        for (var request : requests) request.put("items", rows("SELECT product_id,product_code,product_name,specification,unit_name,quantity FROM mes_portal_request_line WHERE tenant_id=? AND request_id=? ORDER BY id", tenant, request.get("id")));
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String token, OrderRequest body) {
        Identity id = identity(token);
        if (body == null || !text(body.requestKey()).matches("[a-zA-Z0-9-]{16,64}")) throw bad("请提供有效的提交标识");
        if (body.deliveryDate() == null || body.deliveryDate().isBefore(LocalDate.now()) || body.deliveryDate().isAfter(LocalDate.now().plusYears(5))) throw bad("期望交期须在今天至未来五年内");
        if (text(body.remark()).length() > 1000 || body.items() == null || body.items().isEmpty() || body.items().size() > 50) throw bad("订购申请须包含1至50条产品，备注不超过1000字");
        var existing = rows("SELECT id,code,status FROM mes_portal_request WHERE tenant_id=? AND account_id=? AND request_key=?", id.tenantId(), id.accountId(), body.requestKey());
        if (!existing.isEmpty()) return existing.get(0);
        Map<Long, Map<String,Object>> available = new HashMap<>();
        for (var product : catalog(token)) available.put(number(product,"productId"),product);
        Set<Long> distinct = new HashSet<>();
        for (var line : body.items()) {
            if (line == null || line.productId() == null || !available.containsKey(line.productId()) || !distinct.add(line.productId())) throw bad("所选产品不可订购或重复，请刷新目录");
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().compareTo(new BigDecimal("1000000")) > 0 || line.quantity().stripTrailingZeros().scale() > 6) throw bad("数量须大于0、不超过1000000，最多6位小数");
        }
        String code = "CP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        long requestId;
        try {
            requestId = insert("INSERT INTO mes_portal_request(tenant_id,client_id,account_id,code,request_key,delivery_date,remark,status) VALUES(?,?,?,?,?,?,?,'PENDING')", id.tenantId(), id.clientId(), id.accountId(), code, body.requestKey(), body.deliveryDate(), text(body.remark()));
        } catch (DuplicateKeyException conflict) {
            // A locking read observes a concurrently committed duplicate under MySQL REPEATABLE READ.
            var duplicate = rows("SELECT id,code,status FROM mes_portal_request WHERE tenant_id=? AND account_id=? AND request_key=? FOR UPDATE", id.tenantId(), id.accountId(), body.requestKey());
            if (!duplicate.isEmpty()) return duplicate.get(0);
            throw conflict;
        }
        for (var line : body.items()) {
            var product = available.get(line.productId());
            jdbc.update("INSERT INTO mes_portal_request_line(tenant_id,request_id,product_id,product_code,product_name,specification,unit_name,quantity) VALUES(?,?,?,?,?,?,?,?)", id.tenantId(), requestId, line.productId(), product.get("productCode"), product.get("productName"), Objects.toString(product.get("specification"), ""), product.get("unitName"), line.quantity());
        }
        return Map.of("id", requestId, "code", code, "status", "PENDING");
    }

    public List<Map<String, Object>> accounts() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        return rows("SELECT a.id,a.client_id,c.name AS client_name,a.username,a.display_name,a.enabled,a.expire_time,a.failed_attempts,a.locked_until,a.create_time FROM mes_portal_account a LEFT JOIN mes_md_client c ON c.id=a.client_id AND c.tenant_id=a.tenant_id AND c.deleted=0 WHERE a.tenant_id=? AND a.deleted=0 ORDER BY a.id DESC LIMIT 500", tenant).stream().map(MesReportTimeFormat::isoResponse).toList();
    }
    public List<Map<String, Object>> clients() {
        return rows("SELECT id,code,name FROM mes_md_client WHERE tenant_id=? AND deleted=0 AND status=0 ORDER BY code LIMIT 1000", TenantContextHolder.getRequiredTenantId());
    }
    public List<Map<String, Object>> products() {
        return rows("SELECT id,code,name,specification FROM mes_md_item WHERE tenant_id=? AND deleted=0 AND status=0 ORDER BY code LIMIT 2000", TenantContextHolder.getRequiredTenantId());
    }
    @Transactional(rollbackFor = Exception.class)
    public long saveAccount(AccountSave body) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        if (body == null || body.clientId() == null || !text(body.username()).matches("[a-zA-Z0-9._@-]{3,64}") || text(body.displayName()).isEmpty() || text(body.displayName()).length() > 100 || body.enabled() == null) throw bad("请填写有效客户、账号、显示名称和启用状态");
        if (rows("SELECT id FROM mes_md_client WHERE id=? AND tenant_id=? AND deleted=0 AND status=0", body.clientId(), tenant).isEmpty()) throw bad("绑定客户不存在或不可用");
        if (body.enabled() && body.expireTime() != null && !body.expireTime().isAfter(LocalDateTime.now())) throw bad("启用账号的到期时间须晚于当前时间");
        String password = body.password() == null ? "" : body.password();
        if (body.id() == null && password.isEmpty()) throw bad("新建账号须设置密码");
        if (!password.isEmpty() && (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72)) throw bad("密码至少10个字符，UTF-8编码不超过72字节");
        String username = text(body.username()).toLowerCase(Locale.ROOT);
        if (!rows("SELECT id FROM mes_portal_account WHERE tenant_id=? AND username=? AND (? IS NULL OR id<>?)", tenant, username, body.id(), body.id()).isEmpty()) throw bad("该客户账号已存在");
        if (body.id() == null) return insert("INSERT INTO mes_portal_account(tenant_id,client_id,username,display_name,password_hash,enabled,expire_time) VALUES(?,?,?,?,?,?,?)", tenant, body.clientId(), username, text(body.displayName()), passwords.encode(password), body.enabled(), body.expireTime());
        var current = rows("SELECT id,password_hash FROM mes_portal_account WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE", body.id(), tenant);
        if (current.isEmpty()) throw bad("客户账号不存在");
        jdbc.update("UPDATE mes_portal_account SET client_id=?,username=?,display_name=?,password_hash=?,enabled=?,expire_time=?,auth_version=auth_version+1,failed_attempts=0,locked_until=NULL,update_time=? WHERE id=? AND tenant_id=? AND deleted=0", body.clientId(), username, text(body.displayName()), password.isEmpty() ? current.get(0).get("passwordHash") : passwords.encode(password), body.enabled(), body.expireTime(), LocalDateTime.now(), body.id(), tenant);
        jdbc.update("UPDATE mes_portal_session SET revoked=1 WHERE tenant_id=? AND account_id=?", tenant, body.id());
        return body.id();
    }
    public List<Map<String, Object>> adminCatalog() {
        return rows("SELECT p.product_id,i.code AS product_code,i.name AS product_name,i.specification,p.description,p.enabled FROM mes_portal_catalog p LEFT JOIN mes_md_item i ON i.id=p.product_id AND i.tenant_id=p.tenant_id AND i.deleted=0 WHERE p.tenant_id=? ORDER BY p.id DESC LIMIT 1000", TenantContextHolder.getRequiredTenantId());
    }
    @Transactional(rollbackFor = Exception.class)
    public void saveCatalog(CatalogSave body) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        if (body == null || body.productId() == null || body.enabled() == null || text(body.description()).length() > 1000) throw bad("请选择产品，描述不超过1000字");
        if (rows("SELECT id FROM mes_md_item WHERE id=? AND tenant_id=? AND deleted=0 AND status=0", body.productId(), tenant).isEmpty()) throw bad("产品不存在或不可用");
        var existing = rows("SELECT id FROM mes_portal_catalog WHERE tenant_id=? AND product_id=?", tenant, body.productId());
        if (existing.isEmpty()) jdbc.update("INSERT INTO mes_portal_catalog(tenant_id,product_id,description,enabled) VALUES(?,?,?,?)", tenant, body.productId(), text(body.description()), body.enabled());
        else jdbc.update("UPDATE mes_portal_catalog SET description=?,enabled=?,update_time=? WHERE tenant_id=? AND product_id=?", text(body.description()), body.enabled(), LocalDateTime.now(), tenant, body.productId());
    }
    public List<Map<String, Object>> adminRequests() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        var requests = rows("SELECT r.id,r.code,r.client_id,c.name AS client_name,r.delivery_date,r.remark,r.status,r.review_note,r.review_time,r.create_time FROM mes_portal_request r JOIN mes_md_client c ON c.id=r.client_id AND c.tenant_id=r.tenant_id AND c.deleted=0 WHERE r.tenant_id=? ORDER BY r.id DESC LIMIT 300", tenant);
        attachLines(requests, tenant);
        return requests.stream().map(MesReportTimeFormat::isoResponse).toList();
    }
    @Transactional(rollbackFor = Exception.class)
    public void review(ReviewRequest body, Long reviewerId) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        if (body == null || body.id() == null || !Set.of("ACCEPTED", "REJECTED").contains(text(body.status())) || text(body.reviewNote()).isEmpty() || text(body.reviewNote()).length() > 1000) throw bad("请选择受理或拒绝，并填写不超过1000字的客户可见反馈");
        if (jdbc.update("UPDATE mes_portal_request SET status=?,review_note=?,reviewer_id=?,review_time=? WHERE id=? AND tenant_id=? AND status='PENDING'", body.status(), text(body.reviewNote()), reviewerId, LocalDateTime.now(), body.id(), tenant) != 1) throw bad("申请不存在或已处理，请刷新");
    }
}
