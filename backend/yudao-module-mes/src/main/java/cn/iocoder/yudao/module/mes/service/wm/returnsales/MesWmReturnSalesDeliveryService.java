package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/** Sales returns are authorized by immutable actual delivery evidence, independent of batch naming dimensions. */
@Service
public class MesWmReturnSalesDeliveryService {
    @Resource private JdbcTemplate jdbcTemplate;
    @Resource private MesMdItemService itemService;
    @Resource private MesWmBatchService batchService;

    public void validateBatchCode(Long itemId, Long batchId, String batchCode) {
        String expected = batchId == null ? null : batchService.validateBatchExists(batchId, itemId).getCode();
        if (!Objects.equals(code(expected), code(batchCode))) throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
    }

    public void validateDelivery(Long clientId, Long itemId, Long batchId) {
        var item = itemService.validateItemExistsAndEnable(itemId);
        if (Boolean.TRUE.equals(item.getBatchFlag()) && batchId == null) throw exception(MD_ITEM_BATCH_REQUIRED);
        long tenant = TenantContextHolder.getRequiredTenantId();
        String canonicalBatchCode = null;
        if (batchId != null) {
            // Keep the generic customer/vendor API strict. A batch may intentionally omit the customer dimension.
            canonicalBatchCode = batchService.validateBatchExists(batchId, itemId).getCode();
            if (jdbcTemplate.queryForList("SELECT id FROM mes_wm_batch WHERE id=? AND item_id=? AND tenant_id=? AND deleted=0",
                    batchId, itemId, tenant).isEmpty()) throw exception(WM_BATCH_NOT_EXISTS);
        }
        if (clientId == null) throw exception(WM_RETURN_SALES_DELIVERY_REQUIRED);
        var candidates = jdbcTemplate.queryForList("""
                SELECT DISTINCT s.id AS sales_id,l.id AS line_id,l.quantity,l.batch_id,l.batch_code FROM mes_wm_product_sales s
                JOIN mes_wm_product_sales_line l ON l.sales_id=s.id AND l.tenant_id=s.tenant_id AND l.deleted=0
                JOIN mes_wm_transaction t ON t.biz_id=s.id AND t.biz_line_id=l.id AND t.tenant_id=s.tenant_id AND t.deleted=0
                WHERE s.tenant_id=? AND s.deleted=0 AND s.status=4 AND s.client_id=?
                    AND l.item_id=? AND t.item_id=l.item_id AND l.quantity>0 AND l.quality_status=1
                    AND t.biz_type=118 AND t.type=2 AND t.quantity<0
                    AND (t.batch_id=? OR (t.batch_id IS NULL AND ? IS NULL))
                    AND (l.batch_id IS NULL OR l.batch_id=t.batch_id)
                    AND (l.batch_code IS NULL OR l.batch_code='' OR l.batch_code=t.batch_code)
                ORDER BY s.id,l.id
                """, tenant, clientId, itemId, batchId, batchId);
        // Completed sales cannot be edited/cancelled through business services. Do not retain locks on old
        // shipments or their ledgers: a new sale refreshes the same notice after locking stock. An older RR
        // snapshot can conservatively require a retry, but cannot invent previously absent delivery evidence.
        for (var line : candidates) if (matchesActualPosting(tenant, itemId, batchId, canonicalBatchCode, line)) return;
        throw exception(WM_RETURN_SALES_DELIVERY_REQUIRED);
    }

    boolean matchesActualPosting(long tenant, Long itemId, Long requestedBatch, String canonicalBatchCode, Map<String,Object> line) {
        Long salesId = id(line.get("sales_id")), lineId = id(line.get("line_id"));
        var postings = jdbcTemplate.queryForList("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=118 AND biz_line_id=?", tenant, lineId);
        var details = jdbcTemplate.queryForList("SELECT * FROM mes_wm_product_sales_detail WHERE tenant_id=? AND line_id=?", tenant, lineId);
        Map<StockKey, BigDecimal> posted = new HashMap<>(), picked = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean requested = false;
        for (var row : postings) {
            BigDecimal quantity = quantity(row);
            if (deleted(row) || !Objects.equals(id(row.get("biz_id")), salesId)
                    || !Objects.equals(id(row.get("item_id")), itemId) || !Objects.equals(id(row.get("type")), 2L)
                    || quantity == null || quantity.signum() >= 0 || !matchesSpecifiedBatch(line, row)) return false;
            StockKey key = stockKey(row);
            if (!key.valid()) return false;
            if (Objects.equals(key.batchId(), requestedBatch) && !Objects.equals(key.batchCode(), canonicalBatchCode)) return false;
            posted.merge(key, quantity.negate(), BigDecimal::add);
            total = total.subtract(quantity);
            if (Objects.equals(key.batchId(), requestedBatch)) requested = true;
        }
        for (var row : details) {
            BigDecimal quantity = quantity(row);
            if (deleted(row) || !Objects.equals(id(row.get("sales_id")), salesId)
                    || !Objects.equals(id(row.get("item_id")), itemId) || quantity == null || quantity.signum() <= 0
                    || !matchesSpecifiedBatch(line, row)) return false;
            StockKey key = stockKey(row);
            if (!key.valid()) return false;
            picked.merge(key, quantity, BigDecimal::add);
        }
        if (!requested || total.compareTo(quantity(line)) != 0 || !posted.keySet().equals(picked.keySet())) return false;
        for (var key : posted.keySet()) if (posted.get(key).compareTo(picked.get(key)) != 0) return false;
        return true;
    }

    private boolean matchesSpecifiedBatch(Map<String,Object> line, Map<String,Object> row) {
        return (line.get("batch_id") == null || Objects.equals(id(line.get("batch_id")), id(row.get("batch_id"))))
                && (code(line.get("batch_code")) == null || Objects.equals(code(line.get("batch_code")), code(row.get("batch_code"))));
    }
    private record StockKey(Long itemId,Long batchId,String batchCode,Long stockId,Long warehouseId,Long locationId,Long areaId) {
        boolean valid(){return stockId!=null&&warehouseId!=null&&locationId!=null&&areaId!=null&&(batchId!=null||batchCode==null);}
    }
    private StockKey stockKey(Map<String,Object> row){return new StockKey(id(row.get("item_id")),id(row.get("batch_id")),code(row.get("batch_code")),id(row.get("material_stock_id")),id(row.get("warehouse_id")),id(row.get("location_id")),id(row.get("area_id")));}
    private Long id(Object value){return value==null?null:((Number)value).longValue();}
    private String code(Object value){return value==null||value.toString().isBlank()?null:value.toString();}
    private BigDecimal quantity(Map<String,Object> row){Object v=row.get("quantity");return v==null?null:new BigDecimal(v.toString());}
    private boolean deleted(Map<String,Object> row){Object v=row.get("deleted");return Boolean.TRUE.equals(v)||(v instanceof Number&&((Number)v).intValue()!=0);}
}
