package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registry/config writes share this order. The global key prevents RR empty-range gaps between tenants. */
@Component
public class MesWmBarcodeMutex {
    @Resource private JdbcTemplate jdbc;
    public void lock(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("条码写入需要外层事务");
        long tenant=TenantContextHolder.getRequiredTenantId();if(tenant<0)throw MesWmBarcodeLabelPolicy.invalid("租户编号无效");
        // The migration seeds this existing PK. Do not INSERT IGNORE the shared key:
        // duplicate-key shared locks could both attempt an upgrade before SELECT FOR UPDATE.
        jdbc.queryForObject("SELECT tenant_id FROM mes_wm_barcode_mutex WHERE tenant_id=-1 FOR UPDATE",Long.class);
        lockKey(tenant);
    }
    private void lockKey(long key){jdbc.update("INSERT IGNORE INTO mes_wm_barcode_mutex(tenant_id) VALUES(?)",key);jdbc.queryForObject("SELECT tenant_id FROM mes_wm_barcode_mutex WHERE tenant_id=? FOR UPDATE",Long.class,key);}
}
