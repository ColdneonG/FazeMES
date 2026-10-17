package cn.iocoder.yudao.module.mes.service.dv.task;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.checkplan.MesDvCheckPlanMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
/** Quartz-independent, tenant-aware startup compensation and periodic retry. */
@Component @EnableScheduling @Slf4j
public class MesDvTaskScheduler {
    @Resource private MesDvTaskService taskService;
    @Resource private MesDvCheckPlanMapper planMapper;
    @Resource private ObjectProvider<TenantFrameworkService> tenants;
    @Value("${mes.dv.tasks.enabled:true}") private boolean enabled;
    @Value("${yudao.tenant.enable:true}") private boolean tenantEnabled;
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {scan();}
    @Scheduled(initialDelayString="${mes.dv.tasks.interval-ms:60000}",fixedDelayString="${mes.dv.tasks.interval-ms:60000}")
    public void scan() {
        if(!enabled) return;
        if(!tenantEnabled) {TenantUtils.executeIgnore(this::scanTenant);return;}
        TenantFrameworkService service=tenants.getIfAvailable();
        if(service==null) {log.error("Device task scheduler requires tenant service");return;}
        try {
            for(Long tenantId:service.getTenantIds()) {
                try {service.validTenant(tenantId);TenantUtils.execute(tenantId,this::scanTenant);}
                catch(Exception ex) {log.error("Device task scan failed for tenant {}",tenantId,ex);}
            }
        } catch(Exception ex) {log.error("Device task tenant enumeration failed",ex);}
    }
    private void scanTenant() {
        LocalDateTime now=LocalDateTime.now().withNano(0);
        for(var plan:planMapper.selectEnabled()) {
            try {taskService.generateDueTasks(plan.getId(),now);}
            catch(Exception ex) {log.error("Device task generation failed for plan {}",plan.getId(),ex);}
        }
    }
}
