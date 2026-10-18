package cn.iocoder.yudao.framework.apilog.core.util;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiLogSanitizerTest {
    @Test void removesNormalizedCredentialsAtAllObjectAndArrayDepths() throws Exception {
        String input = """
                {"username":"visible-user","items":[{"Password":"test-password-value","access_token":"test-access-value"}],
                 "nested":{"refresh-token":"test-refresh-value","client_secret":"test-secret-value","PRIVATE_KEY":"test-private-value"},
                 "count":3,"oldPassword":"test-old-value","headers":{"Authorization":"test-header-value"}}
                """;
        String output = ApiLogSanitizer.sanitizeJson(input, null);
        var tree = JsonUtils.getObjectMapper().readTree(output);
        assertEquals("visible-user", tree.path("username").asText()); assertEquals(3, tree.path("count").asInt());
        for (String value : new String[]{"test-password-value", "test-access-value", "test-refresh-value", "test-secret-value", "test-private-value", "test-old-value", "test-header-value"}) {
            assertFalse(output.contains(value));
        }
        assertTrue(tree.path("items").get(0).isEmpty()); assertTrue(tree.path("nested").isEmpty());
    }
    @Test void handlesImmutableQueryMapsAndJsonEncodedValuesWithoutChangingTheRequest() throws Exception {
        var input = Map.of("user[password]", "test-query-password", "refresh_token", "test-query-refresh",
                "socialCode", "test-social-code", "pageNo", "2", "filter", "{\"token\":\"test-filter-token\",\"itemCode\":\"A001\"}");
        String output = ApiLogSanitizer.sanitizeMap(input, new String[]{"social_code"});
        var tree = JsonUtils.getObjectMapper().readTree(output);
        assertEquals("2", tree.path("pageNo").asText());
        assertTrue(tree.path("filter").asText().contains("A001"));
        assertFalse(output.contains("test-query")); assertFalse(output.contains("test-social")); assertFalse(output.contains("test-filter-token"));
        assertEquals("test-query-password", input.get("user[password]")); assertEquals(5, input.size());
    }
    @Test void malformedAndUnstructuredBodiesAreOmittedInsteadOfEchoingSourceOrParserDetails() {
        for (String input : new String[]{"{\"password\":\"test-invalid-secret", "\"test-scalar-token\"", "null", "test-plain-secret"}) {
            assertEquals(ApiLogSanitizer.OMITTED, ApiLogSanitizer.sanitizeJson(input, null));
        }
        assertNull(ApiLogSanitizer.sanitizeJson(null, null));
        assertNull(ApiLogSanitizer.sanitizeMap(Map.of(), null));
    }
    @Test void responseTokensAndJsonStringsInsideArraysAreFilteredWhileBusinessFieldsRemain() throws Exception {
        var result = CommonResult.success(Map.of("accessToken", "test-response-access", "refreshToken", "test-response-refresh", "userId", 7));
        var response = JsonUtils.getObjectMapper().readTree(ApiLogSanitizer.sanitizeObject(result, null));
        assertEquals(0, response.path("code").asInt()); assertEquals(7, response.path("data").path("userId").asInt());
        assertFalse(response.path("data").has("accessToken")); assertFalse(response.path("data").has("refreshToken"));
        String array = ApiLogSanitizer.sanitizeJson("[\"{\\\"password\\\":\\\"test-array-secret\\\",\\\"name\\\":\\\"visible\\\"}\"]", null);
        assertFalse(array.contains("test-array-secret")); assertTrue(array.contains("visible"));
    }
}
