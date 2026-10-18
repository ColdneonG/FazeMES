package cn.iocoder.yudao.module.mes.dal.mysql.md.item;

import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.MesMdStockWarningRespVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MesMdStockWarningMapper {
    @Select("""
        <script>
        SELECT i.id AS item_id, i.code, i.name,
               COALESCE(SUM(s.quantity), 0) AS stock_quantity,
               COALESCE(i.min_stock, 0) AS min_stock, COALESCE(i.max_stock, 0) AS max_stock
        FROM mes_md_item i
        LEFT JOIN mes_wm_material_stock s ON s.item_id = i.id
          AND s.deleted = FALSE AND s.tenant_id = i.tenant_id
        WHERE i.deleted = FALSE AND i.status = 0 AND i.safe_stock_flag = TRUE
        <if test="keyword != null and keyword != ''">
          AND (i.code LIKE CONCAT('%', #{keyword}, '%') OR i.name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        GROUP BY i.id, i.code, i.name, i.min_stock, i.max_stock
        ORDER BY i.code
        </script>
        """)
    List<MesMdStockWarningRespVO> selectStockLevels(@Param("keyword") String keyword);
}
