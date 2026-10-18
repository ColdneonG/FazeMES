package cn.iocoder.yudao.framework.apilog.core.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import cn.iocoder.yudao.framework.apilog.core.util.ApiLogSanitizer;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.common.util.spring.SpringUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyWrapper;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise the real interceptor/filter path, not just a detached masking helper. */
class ApiAccessLogSanitizationTest {
    private final ApiAccessLogCommonApi api = mock(ApiAccessLogCommonApi.class);
    private final WebProperties properties = new WebProperties();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    @BeforeEach void before() {
        new WebFrameworkUtils(properties);
        previousLevel = logger.getLevel(); logger.setLevel(Level.INFO);
        appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }
    @AfterEach void after() { logger.detachAppender(appender); appender.stop(); logger.setLevel(previousLevel); }

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", "/admin-api/system/auth/" + path);
        request.setServletPath(request.getRequestURI()); return request;
    }
    private HttpServletRequest jsonRequest(String path, String body) {
        var request = request(path); request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return new CacheRequestBodyWrapper(request);
    }
    private ApiAccessLogCreateReqDTO exercise(HttpServletRequest request, String method) throws Exception {
        var handler = new HandlerMethod(new TestController(), TestController.class.getMethod(method));
        var interceptor = new ApiAccessLogInterceptor();
        var response = new MockHttpServletResponse();
        try (MockedStatic<SpringUtils> environment = mockStatic(SpringUtils.class)) {
            environment.when(SpringUtils::isProd).thenReturn(false);
            new ApiAccessLogFilter(properties, "FazeMES", api).doFilter(request, response, (req, resp) -> {
                interceptor.preHandle(request, response, handler);
                WebFrameworkUtils.setCommonResult(request, CommonResult.success(Map.of("accessToken", "test-access-token", "refreshToken", "test-refresh-token", "userId", 7)));
                interceptor.afterCompletion(request, response, handler, null);
            });
        }
        var log = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(api).createApiAccessLogAsync(log.capture()); return log.getValue();
    }
    private String console() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat); }

    @Test void loginBodyAndResponseCredentialsAreAbsentFromConsoleAndPersistence() throws Exception {
        HttpServletRequest request = jsonRequest("login", "{\"username\":\"visible-user\",\"password\":\"test-login-password\",\"socialCode\":\"test-social-code\"}");
        var record = exercise(request, "login");
        String all = console() + JsonUtils.toJsonString(record);
        for (String value : new String[]{"test-login-password", "test-social-code", "test-access-token", "test-refresh-token"}) assertFalse(all.contains(value));
        assertTrue(console().contains("visible-user")); assertTrue(record.getRequestParams().contains("visible-user"));
        assertTrue(record.getResponseBody().contains("userId")); assertEquals(0, record.getResultCode());
        assertTrue(ServletUtils.getBody(request).contains("test-login-password"));
    }
    @Test void refreshQueryTokenIsRemovedAndOriginalParametersArePreserved() throws Exception {
        var request = request("refresh-token");
        request.addParameter("refresh_token", "test-refresh-query"); request.addParameter("clientId", "visible-client");
        var record = exercise(request, "refresh");
        assertFalse((console() + record.getRequestParams()).contains("test-refresh-query"));
        assertTrue(console().contains("visible-client")); assertEquals("test-refresh-query", request.getParameter("refresh_token"));
    }
    @Test void requestEnableAppliesToConsoleAndDatabaseWithoutDisablingBusinessMetadata() throws Exception {
        var record = exercise(jsonRequest("private", "{\"arbitrary\":\"test-hidden-payload\"}"), "privateRequest");
        assertNull(record.getRequestParams()); assertFalse(console().contains("test-hidden-payload"));
        assertTrue(console().contains("开始请求")); assertNotNull(record.getDuration());
    }
    @Test void malformedBodyCannotLeakThroughConsoleOrPersistenceFallback() throws Exception {
        var record = exercise(jsonRequest("login", "{\"password\":\"test-malformed-secret"), "login");
        assertFalse((console() + JsonUtils.toJsonString(record)).contains("test-malformed-secret"));
        assertTrue(console().contains(ApiLogSanitizer.OMITTED));
    }
    @Test void exceptionRequestPersistenceUsesTheSameCustomAndDefaultFiltering() throws Exception {
        var errorApi = mock(ApiErrorLogCommonApi.class);
        var request = jsonRequest("login", "{\"password\":\"test-error-password\",\"socialCode\":\"test-error-code\",\"username\":\"visible-user\"}");
        request.setAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD,
                new HandlerMethod(new TestController(), TestController.class.getMethod("login")));
        ReflectionTestUtils.invokeMethod(new GlobalExceptionHandler("FazeMES", errorApi), "createExceptionLog", request, new IllegalStateException("test failure"));
        var log = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(errorApi).createApiErrorLogAsync(log.capture());
        assertFalse(log.getValue().getRequestParams().contains("test-error-password"));
        assertFalse(log.getValue().getRequestParams().contains("test-error-code"));
        assertTrue(log.getValue().getRequestParams().contains("visible-user"));
    }

    public static class TestController {
        @ApiAccessLog(responseEnable = true, sanitizeKeys = "socialCode") public void login() { }
        public void refresh() { }
        @ApiAccessLog(requestEnable = false) public void privateRequest() { }
    }
}
