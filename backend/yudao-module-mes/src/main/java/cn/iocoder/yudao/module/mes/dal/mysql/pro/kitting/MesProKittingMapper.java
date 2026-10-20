package cn.iocoder.yudao.module.mes.dal.mysql.pro.kitting;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo.KittingMaterialVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting.MesProMaterialReservationDO;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface MesProKittingMapper extends BaseMapperX<MesProMaterialReservationDO> {
    String STOCK_COLUMNS = """
        SELECT s.id,s.item_id,s.area_id,s.warehouse_id,s.location_id,s.batch_id,s.batch_code,
          s.quantity,s.receipt_time,w.name warehouse_name,l.name location_name,a.name area_name,
          (s.work_order_id IS NULL AND w.code NOT IN ('WIP_VIRTUAL_WAREHOUSE','QUALITY_QUARANTINE_WAREHOUSE')
           AND s.frozen=FALSE AND COALESCE(s.quality_hold,FALSE)=FALSE AND w.frozen=FALSE AND l.frozen=FALSE AND a.frozen=FALSE
           AND a.status=0 AND s.quantity &gt; 0) AS eligible
        FROM mes_wm_material_stock s
        JOIN mes_wm_warehouse w ON w.id=s.warehouse_id AND w.tenant_id=s.tenant_id AND w.deleted=FALSE
        JOIN mes_wm_warehouse_location l ON l.id=s.location_id AND l.tenant_id=s.tenant_id AND l.deleted=FALSE
        JOIN mes_wm_warehouse_area a ON a.id=s.area_id AND a.tenant_id=s.tenant_id AND a.deleted=FALSE
        WHERE s.tenant_id=#{tenantId} AND s.deleted=FALSE
        """;

    @Select("""
        SELECT b.item_id,i.code item_code,i.name item_name,u.name unit_name,SUM(b.quantity) required_quantity
        FROM mes_pro_work_order_bom b
        JOIN mes_md_item i ON i.id=b.item_id AND i.tenant_id=b.tenant_id AND i.deleted=FALSE
        LEFT JOIN mes_md_unit_measure u ON u.id=i.unit_measure_id AND u.tenant_id=i.tenant_id AND u.deleted=FALSE
        WHERE b.work_order_id=#{workOrderId} AND b.tenant_id=#{tenantId} AND b.deleted=FALSE
        GROUP BY b.item_id,i.code,i.name,u.name ORDER BY b.item_id
        """)
    List<KittingMaterialVO> selectRequirements(@Param("workOrderId") Long workOrderId, @Param("tenantId") Long tenantId);

    @Select("<script>" + STOCK_COLUMNS + """
        AND s.work_order_id IS NULL AND w.code NOT IN ('WIP_VIRTUAL_WAREHOUSE','QUALITY_QUARANTINE_WAREHOUSE')
        AND s.item_id IN <foreach collection='itemIds' item='item' open='(' close=')' separator=','>#{item}</foreach>
        AND s.quantity &gt; 0 ORDER BY s.receipt_time,s.id
        </script>
        """)
    List<KittingStockRow> selectCandidates(@Param("itemIds") List<Long> itemIds, @Param("tenantId") Long tenantId);

    @Select("<script>" + STOCK_COLUMNS + " AND s.id=#{stockId}</script>")
    KittingStockRow selectStock(@Param("stockId") Long stockId, @Param("tenantId") Long tenantId);

    // Only the stock row is locked. Joining the resource tables in this locking
    // query would lock warehouses/locations in stock order and reintroduce cycles
    // between disjoint areas in different warehouses. Area locks precede this.
    @Select("""
        SELECT s.id,s.item_id,s.area_id,s.warehouse_id,s.location_id,s.batch_id,s.batch_code,
          s.quantity,s.receipt_time,
          (SELECT w.name FROM mes_wm_warehouse w WHERE w.id=s.warehouse_id AND w.tenant_id=s.tenant_id AND w.deleted=FALSE) warehouse_name,
          (SELECT l.name FROM mes_wm_warehouse_location l WHERE l.id=s.location_id AND l.tenant_id=s.tenant_id AND l.deleted=FALSE) location_name,
          (SELECT a.name FROM mes_wm_warehouse_area a WHERE a.id=s.area_id AND a.tenant_id=s.tenant_id AND a.deleted=FALSE) area_name,
          (s.work_order_id IS NULL AND s.frozen=FALSE AND COALESCE(s.quality_hold,FALSE)=FALSE AND s.quantity > 0
            AND EXISTS (SELECT 1 FROM mes_wm_warehouse w WHERE w.id=s.warehouse_id AND w.tenant_id=s.tenant_id
              AND w.deleted=FALSE AND w.frozen=FALSE AND w.code NOT IN ('WIP_VIRTUAL_WAREHOUSE','QUALITY_QUARANTINE_WAREHOUSE'))
            AND EXISTS (SELECT 1 FROM mes_wm_warehouse_location l WHERE l.id=s.location_id AND l.tenant_id=s.tenant_id
              AND l.deleted=FALSE AND l.frozen=FALSE)
            AND EXISTS (SELECT 1 FROM mes_wm_warehouse_area a WHERE a.id=s.area_id AND a.tenant_id=s.tenant_id
              AND a.deleted=FALSE AND a.frozen=FALSE AND a.status=0)) eligible
        FROM mes_wm_material_stock s
        WHERE s.tenant_id=#{tenantId} AND s.deleted=FALSE AND s.id=#{stockId} FOR UPDATE
        """)
    KittingStockRow lockStock(@Param("stockId") Long stockId, @Param("tenantId") Long tenantId);

    @Select("SELECT id FROM mes_wm_warehouse_area WHERE id=#{areaId} AND tenant_id=#{tenantId} AND deleted=FALSE FOR UPDATE")
    Long lockArea(@Param("areaId") Long areaId, @Param("tenantId") Long tenantId);

    @Select("SELECT * FROM mes_pro_material_reservation WHERE work_order_id=#{workOrderId} AND tenant_id=#{tenantId} AND deleted=FALSE ORDER BY id")
    List<MesProMaterialReservationDO> selectForOrder(@Param("workOrderId") Long workOrderId, @Param("tenantId") Long tenantId);

    @Select("SELECT * FROM mes_pro_material_reservation WHERE material_stock_id=#{stockId} AND tenant_id=#{tenantId} AND deleted=FALSE ORDER BY id FOR UPDATE")
    List<MesProMaterialReservationDO> lockForStock(@Param("stockId") Long stockId, @Param("tenantId") Long tenantId);

    @Select("""
        <script>SELECT * FROM mes_pro_material_reservation WHERE tenant_id=#{tenantId} AND deleted=FALSE
        AND quantity &gt; 0 AND item_id IN
        <foreach collection='itemIds' item='item' open='(' close=')' separator=','>#{item}</foreach>
        </script>
        """)
    List<MesProMaterialReservationDO> selectActive(@Param("itemIds") List<Long> itemIds, @Param("tenantId") Long tenantId);

    @Select("""
        SELECT COALESCE(SUM(t.quantity),0) FROM mes_wm_transaction t
        JOIN mes_wm_warehouse w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id AND w.deleted=FALSE
        WHERE t.work_order_id=#{workOrderId} AND t.item_id=#{itemId} AND t.tenant_id=#{tenantId}
          AND t.deleted=FALSE AND w.code='WIP_VIRTUAL_WAREHOUSE' AND t.biz_type IN (115,116)
        """)
    BigDecimal selectNetIssued(@Param("workOrderId") Long workOrderId, @Param("itemId") Long itemId, @Param("tenantId") Long tenantId);

    @Select("""
        <script>SELECT id FROM mes_pro_work_order WHERE tenant_id=#{tenantId} AND deleted=FALSE AND status=1
        <if test="keyword != null and keyword != ''">AND (code LIKE CONCAT('%',#{keyword},'%') OR name LIKE CONCAT('%',#{keyword},'%'))</if>
        ORDER BY request_date,id</script>
        """)
    List<Long> selectOpenOrders(@Param("keyword") String keyword, @Param("tenantId") Long tenantId);
}
