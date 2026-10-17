package cn.iocoder.yudao.framework.apilog.core.util;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.*;

/** Shared filtering for access-log console output and persisted request/response data. */
public final class ApiLogSanitizer {

    public static final String OMITTED = "[内容已省略：无法安全解析]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "credential", "credentials",
            "secret", "apikey", "privatekey", "clientkey", "sessionid", "sessionkey", "sessiontoken");

    private ApiLogSanitizer() { }

    public static String sanitizeMap(Map<String, ?> map, String[] extraKeys) {
        if (map == null || map.isEmpty()) return null;
        try {
            // A detached tree keeps the actual request and immutable parameter maps unchanged.
            JsonNode tree = JsonUtils.getObjectMapper().valueToTree(map);
            sanitize(tree, extraKeys);
            return JsonUtils.toJsonString(tree);
        } catch (Exception ignored) {
            return OMITTED;
        }
    }

    public static String sanitizeJson(String json, String[] extraKeys) {
        if (json == null || json.isBlank()) return null;
        try {
            // JsonUtils.parseTree logs the raw input on failure, so use the mapper directly here.
            JsonNode tree = JsonUtils.getObjectMapper().readTree(json);
            if (tree == null || (!tree.isObject() && !tree.isArray())) return OMITTED;
            sanitize(tree, extraKeys);
            return JsonUtils.toJsonString(tree);
        } catch (Exception ignored) {
            // Never emit malformed content or parser exception messages containing the source.
            return OMITTED;
        }
    }

    public static String sanitizeObject(Object value, String[] extraKeys) {
        if (value == null) return null;
        try {
            JsonNode tree = JsonUtils.getObjectMapper().valueToTree(value);
            sanitize(tree, extraKeys);
            return JsonUtils.toJsonString(tree);
        } catch (Exception ignored) {
            return OMITTED;
        }
    }

    private static void sanitize(JsonNode node, String[] extraKeys) {
        if (node == null) return;
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual() && looksStructured(child.textValue())) {
                    array.set(i, TextNode.valueOf(sanitizeJson(child.textValue(), extraKeys)));
                } else sanitize(child, extraKeys);
            }
            return;
        }
        if (!node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (sensitive(field.getKey(), extraKeys)) {
                fields.remove();
            } else if (field.getValue().isTextual() && looksStructured(field.getValue().textValue())) {
                // Query parameters may themselves contain JSON-encoded filter/body objects.
                field.setValue(TextNode.valueOf(sanitizeJson(field.getValue().textValue(), extraKeys)));
            } else {
                sanitize(field.getValue(), extraKeys);
            }
        }
    }

    private static boolean sensitive(String key, String[] extraKeys) {
        String name = normalize(key);
        if (SENSITIVE_KEYS.contains(name) || name.contains("password") || name.endsWith("passwd")
                || name.endsWith("pwd") || name.endsWith("token") || name.equals("tokens")
                || name.endsWith("secret") || name.endsWith("apikey") || name.endsWith("privatekey")) return true;
        if (extraKeys != null) {
            for (String extra : extraKeys) if (name.equals(normalize(extra))) return true;
        }
        return false;
    }

    private static boolean looksStructured(String value) {
        String text = value.stripLeading();
        return text.startsWith("{") || text.startsWith("[");
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
