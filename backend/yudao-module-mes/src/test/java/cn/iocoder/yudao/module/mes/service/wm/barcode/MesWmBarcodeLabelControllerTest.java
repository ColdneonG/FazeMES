package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.MesWmBarcodeController;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.MesWmBarcodeLabelContextRespVO;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Execute the endpoint's real method-security advice without starting an HTTP server. */
class MesWmBarcodeLabelControllerTest {
    private AnnotationConfigApplicationContext context;
    private MesWmBarcodeController controller;
    private MesWmBarcodeService service;
    private Permissions permissions;

    @Configuration @EnableMethodSecurity
    static class TestConfiguration {
        @Bean MesWmBarcodeService barcodeService(){return mock(MesWmBarcodeService.class);}
        @Bean MesWmBarcodeController barcodeController(){return new MesWmBarcodeController();}
        @Bean(name="ss") Permissions permissions(){return new Permissions();}
    }
    public static class Permissions {
        final Set<String> granted=new HashSet<>();
        public boolean hasPermission(String permission){return granted.contains(permission);}
    }
    @BeforeEach void setup(){
        context=new AnnotationConfigApplicationContext(TestConfiguration.class);
        controller=context.getBean(MesWmBarcodeController.class);service=context.getBean(MesWmBarcodeService.class);permissions=context.getBean(Permissions.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator","not-a-provider-key",List.of()));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(controller));
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();context.close();}
    @Test void queryPermissionIsSufficientAndTheEndpointDoesNotRequireCreatePermission(){
        permissions.granted.add("mes:wm-barcode:query");
        var expected=new MesWmBarcodeLabelContextRespVO().setRegistered(false).setBizType(600).setBizId(32L).setBizCode("ITEM-032").setCandidateContent("ITEM-032").setFormat(1).setDefaultTemplate("LABEL_50X30");
        when(service.getLabelContext(null,600,32L)).thenReturn(expected);
        assertSame(expected,controller.getLabelContext(null,600,32L).getData());
        verify(service).getLabelContext(null,600,32L);verifyNoMoreInteractions(service);
        assertFalse(permissions.granted.contains("mes:wm-barcode:create"));
    }
    @Test void createOrSourcePermissionsCannotBypassQueryPermission(){
        permissions.granted.addAll(Set.of("mes:wm-barcode:create","mes:wm-barcode:update","mes:md-item:query"));
        assertThrows(AccessDeniedException.class,()->controller.getLabelContext(null,600,32L));
        verifyNoInteractions(service);
    }
}
