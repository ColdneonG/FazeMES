package cn.iocoder.yudao.module.mes.service.wm.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Idempotent configuration for existing and newly created tenants. Never creates stock or inspection results. */
@Service
public class MesWmQualityQuarantineService {
    @Resource private JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public cn.iocoder.yudao.framework.common.pojo.PageResult<cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo.MesWmQualityHoldRespVO> history(
            cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo.MesWmQualityHoldPageReqVO request) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        String where = " WHERE tenant_id=?";
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenant);
        if (request.getMaterialStockId() != null) {
            where += " AND material_stock_id=?";
            args.add(request.getMaterialStockId());
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_stock_quality_hold" + where, Long.class, args.toArray());
        int size = Math.min(100, Math.max(1, request.getPageSize()));
        long offset = (long) (Math.max(1, request.getPageNo()) - 1) * size;
        args.add(size); args.add(offset);
        var rows = jdbc.query("SELECT id,material_stock_id,source_transaction_id,source_biz_type,source_biz_id,source_biz_line_id,arrival_transaction_id,observed_quantity,reason,status,create_time FROM mes_wm_stock_quality_hold"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                new org.springframework.jdbc.core.BeanPropertyRowMapper<>(cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo.MesWmQualityHoldRespVO.class), args.toArray());
        return new cn.iocoder.yudao.framework.common.pojo.PageResult<>(rows, count);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> initialize() {
        long tenant = TenantContextHolder.getRequiredTenantId();
        jdbc.update("INSERT IGNORE INTO mes_wm_quality_config_lock(tenant_id) VALUES(?)", tenant);
        jdbc.queryForObject("SELECT tenant_id FROM mes_wm_quality_config_lock WHERE tenant_id=? FOR UPDATE", Long.class, tenant);
        Long warehouse = unique("mes_wm_warehouse", MesWmQualityPolicy.QUARANTINE_WAREHOUSE, tenant);
        if (warehouse == null) {
            jdbc.update("INSERT INTO mes_wm_warehouse(code,name,frozen,remark,creator,updater,tenant_id) VALUES(?,?,FALSE,?,'quality-quarantine-init','quality-quarantine-init',?)",
                    MesWmQualityPolicy.QUARANTINE_WAREHOUSE, "不良隔离仓", "SYSTEM_QUALITY_QUARANTINE", tenant);
            warehouse = unique("mes_wm_warehouse", MesWmQualityPolicy.QUARANTINE_WAREHOUSE, tenant);
        }
        Long location = unique("mes_wm_warehouse_location", MesWmQualityPolicy.QUARANTINE_LOCATION, tenant);
        if (location == null) {
            jdbc.update("INSERT INTO mes_wm_warehouse_location(code,name,warehouse_id,frozen,remark,creator,updater,tenant_id) VALUES(?,?,?,FALSE,'SYSTEM_QUALITY_QUARANTINE','quality-quarantine-init','quality-quarantine-init',?)",
                    MesWmQualityPolicy.QUARANTINE_LOCATION, "不良隔离库区", warehouse, tenant);
            location = unique("mes_wm_warehouse_location", MesWmQualityPolicy.QUARANTINE_LOCATION, tenant);
        }
        if (!warehouse.equals(jdbc.queryForObject("SELECT warehouse_id FROM mes_wm_warehouse_location WHERE id=? AND tenant_id=?", Long.class, location, tenant))) {
            throw invalid();
        }
        Long area = unique("mes_wm_warehouse_area", MesWmQualityPolicy.QUARANTINE_AREA, tenant);
        if (area == null) {
            jdbc.update("INSERT INTO mes_wm_warehouse_area(code,name,location_id,status,frozen,allow_item_mixing,allow_batch_mixing,remark,creator,updater,tenant_id) VALUES(?,?,?,0,FALSE,TRUE,TRUE,'SYSTEM_QUALITY_QUARANTINE','quality-quarantine-init','quality-quarantine-init',?)",
                    MesWmQualityPolicy.QUARANTINE_AREA, "不良隔离库位", location, tenant);
            area = unique("mes_wm_warehouse_area", MesWmQualityPolicy.QUARANTINE_AREA, tenant);
        }
        if (!location.equals(jdbc.queryForObject("SELECT location_id FROM mes_wm_warehouse_area WHERE id=? AND tenant_id=?", Long.class, area, tenant))) {
            throw invalid();
        }
        if (!Integer.valueOf(0).equals(jdbc.queryForObject("SELECT status FROM mes_wm_warehouse_area WHERE id=? AND tenant_id=?", Integer.class, area, tenant))
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT allow_item_mixing FROM mes_wm_warehouse_area WHERE id=? AND tenant_id=?", Boolean.class, area, tenant))
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT allow_batch_mixing FROM mes_wm_warehouse_area WHERE id=? AND tenant_id=?", Boolean.class, area, tenant))) {
            throw invalid();
        }
        return Map.of("warehouseId", warehouse, "locationId", location, "areaId", area);
    }

    /** Identifier names are fixed internal constants, never supplied by a request. */
    private Long unique(String table, String code, long tenant) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE UPPER(code)=? AND tenant_id=? ORDER BY id FOR UPDATE",
                Long.class, code, tenant);
        if (ids.size() > 1) throw invalid();
        if (!ids.isEmpty()) {
            if (!"SYSTEM_QUALITY_QUARANTINE".equals(jdbc.queryForObject(
                    "SELECT remark FROM " + table + " WHERE id=? AND tenant_id=?", String.class, ids.get(0), tenant))
                    || !Boolean.FALSE.equals(jdbc.queryForObject("SELECT deleted FROM " + table + " WHERE id=? AND tenant_id=?", Boolean.class, ids.get(0), tenant))
                    || !Boolean.FALSE.equals(jdbc.queryForObject("SELECT frozen FROM " + table + " WHERE id=? AND tenant_id=?", Boolean.class, ids.get(0), tenant))) {
                throw invalid();
            }
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private ServiceException invalid() { return new ServiceException(1_047_180_002, "隔离仓保留配置存在重复或归属冲突，请管理员核对；未创建库存"); }
}
