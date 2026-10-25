package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.mes.controller.admin.assistant.MesAssistantController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MesAssistantControllerPolicyTest extends MesAssistantTestDatabase {
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    static class MethodSecurityConfiguration {}

    @Test void springActuallyRejectsCallsWithoutTheIndependentConfigurationPermission() {
        Set<String> permissions = new HashSet<>();
        SecurityFrameworkService permissionService = new SecurityFrameworkService() {
            public boolean hasPermission(String permission) { return permissions.contains(permission); }
            public boolean hasAnyPermissions(String... values) { return Arrays.stream(values).anyMatch(permissions::contains); }
            public boolean hasRole(String role) { return false; }
            public boolean hasAnyRoles(String... roles) { return false; }
            public boolean hasScope(String scope) { return false; }
            public boolean hasAnyScopes(String... scopes) { return false; }
        };
        // Exercise method permissions with the same trusted tenant/user principal as real requests.
        // A string-only principal deliberately fails the production usage actor checks.
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(10L).setTenantId(1L).setUserType(2),
                new MockHttpServletRequest());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurityConfiguration.class);
            context.registerBean("ss", SecurityFrameworkService.class, () -> permissionService);
            context.registerBean(MesAssistantController.class, () -> new MesAssistantController(service, models, permissionService));
            context.refresh();
            var controller = context.getBean(MesAssistantController.class);
            assertThrows(AccessDeniedException.class, controller::modelConfig);
            assertThrows(AccessDeniedException.class, controller::testModelConfig);
            assertThrows(AccessDeniedException.class, controller::resetModelConfig);
            permissions.add("mes:assistant:query");
            var status = controller.status().getData();
            assertNotNull(status);
            assertFalse(((Map<?, ?>) status).containsKey("budgetReady"),
                    "No quota configuration is needed to read model status");
            assertThrows(AccessDeniedException.class, controller::modelConfig);
            permissions.add("mes:assistant:config");
            assertNotNull(controller.modelConfig().getData());
            permissions.clear(); permissions.add("mes:assistant:chat");
            assertThrows(AccessDeniedException.class, () -> controller.message(new MesAssistantController.Message(1L, "test", List.of())));
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void allEndpointsHaveExplicitPermissionsAndModelSecretsAreExcludedFromRequestLogging() throws Exception {
        for (var method : MesAssistantController.class.getDeclaredMethods()) {
            if (Arrays.stream(method.getAnnotations()).noneMatch(annotation -> annotation.annotationType() == GetMapping.class
                    || annotation.annotationType() == PostMapping.class || annotation.annotationType() == PutMapping.class || annotation.annotationType() == DeleteMapping.class)) continue;
            assertNotNull(method.getAnnotation(PreAuthorize.class), method.getName());
        }
        var save = MesAssistantController.class.getDeclaredMethod("saveModelConfig", MesAssistantModelGateway.Settings.class);
        assertTrue(save.getAnnotation(PreAuthorize.class).value().contains("mes:assistant:config"));
        assertFalse(save.getAnnotation(ApiAccessLog.class).requestEnable());
        assertFalse(save.getAnnotation(ApiAccessLog.class).responseEnable());
        var reset = MesAssistantController.class.getDeclaredMethod("resetModelConfig");
        assertTrue(reset.getAnnotation(PreAuthorize.class).value().contains("mes:assistant:config"));
        assertFalse(reset.getAnnotation(ApiAccessLog.class).requestEnable());
        assertFalse(reset.getAnnotation(ApiAccessLog.class).responseEnable());
        assertTrue(Arrays.asList(save.getAnnotation(ApiAccessLog.class).sanitizeKeys()).contains("apiKey"));
        var message = MesAssistantController.class.getDeclaredMethod("message", MesAssistantController.Message.class);
        assertTrue(message.getAnnotation(PreAuthorize.class).value().contains("mes:assistant:knowledge"));
        assertFalse(message.getAnnotation(ApiAccessLog.class).requestEnable());
    }
}
