package cn.iocoder.yudao.module.mes.service.wm.productreceipt;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductProduceStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmTransactionTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Only completed, qualified production can leave WIP as a finished/semi-finished
 * receipt. Stock quantity alone is insufficient: old batches may mix PASS/FAIL.
 */
@Service
public class MesProductReceiptQualityService {
    @Resource private JdbcTemplate jdbc;

    /** Caller must hold the source work-order lock until all paired transfers commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateAvailable(Long workOrderId, Long itemId, Long batchId, BigDecimal quantity) {
        if (workOrderId == null || itemId == null || quantity == null || quantity.signum() <= 0) {
            throw invalid("产品入库必须关联生产工单、物料及正数量");
        }
        long tenant = TenantContextHolder.getRequiredTenantId();
        // Locking reads deliberately return raw ledger rows. A repeatable-read
        // snapshot/aggregate could miss another receipt committed during a wait.
        List<BigDecimal> qualified = jdbc.queryForList("""
                SELECT t.quantity FROM mes_wm_transaction t
                JOIN mes_wm_product_produce_line l ON l.id=t.biz_line_id AND l.produce_id=t.biz_id
                  AND l.item_id=t.item_id AND l.tenant_id=t.tenant_id AND l.deleted=0
                  AND (l.batch_id=t.batch_id OR (l.batch_id IS NULL AND t.batch_id IS NULL))
                JOIN mes_wm_product_produce p ON p.id=l.produce_id AND p.tenant_id=l.tenant_id AND p.deleted=0
                WHERE t.tenant_id=? AND t.deleted=0 AND t.work_order_id=? AND t.item_id=?
                  AND (t.batch_id=? OR (t.batch_id IS NULL AND ? IS NULL))
                  AND t.biz_type=? AND t.type=? AND t.quantity>0
                  AND p.work_order_id=? AND p.status=? AND l.quality_status=?
                ORDER BY t.id FOR UPDATE
                """, BigDecimal.class, tenant, workOrderId, itemId, batchId, batchId,
                MesBizTypeConstants.WM_PRODUCT_PRODUCE, MesWmTransactionTypeEnum.IN.getType(),
                workOrderId, MesWmProductProduceStatusEnum.FINISHED.getStatus(), MesWmQualityStatusEnum.PASS.getStatus());
        List<BigDecimal> transferred = jdbc.queryForList("""
                SELECT quantity FROM mes_wm_transaction
                WHERE tenant_id=? AND deleted=0 AND work_order_id=? AND item_id=?
                  AND (batch_id=? OR (batch_id IS NULL AND ? IS NULL))
                  AND biz_type=? AND type=? AND quantity<0
                ORDER BY id FOR UPDATE
                """, BigDecimal.class, tenant, workOrderId, itemId, batchId, batchId,
                MesBizTypeConstants.WM_PRODUCT_RECPT, MesWmTransactionTypeEnum.OUT.getType());
        BigDecimal produced = sum(qualified);
        BigDecimal available = produced.add(sum(transferred)); // receipt OUT rows are negative
        if (produced.signum() <= 0 || quantity.compareTo(available) > 0) {
            throw invalid("本工单本批次可转入仓库的已完成合格产出不足，请核对检验结果及已入库数量；可用数量："
                    + available.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
    }

    private BigDecimal sum(List<BigDecimal> quantities) {
        return quantities.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ServiceException invalid(String message) { return new ServiceException(1_047_160_010, message); }
}
