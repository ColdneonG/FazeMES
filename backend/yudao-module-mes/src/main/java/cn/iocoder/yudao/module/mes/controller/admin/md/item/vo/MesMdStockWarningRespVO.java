package cn.iocoder.yudao.module.mes.controller.admin.md.item.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "物料实物仓库库存阈值预警（不含线边库）")
public class MesMdStockWarningRespVO {
    private Long itemId;
    private String code;
    private String name;
    private BigDecimal stockQuantity;
    private BigDecimal minStock;
    private BigDecimal maxStock;
    @Schema(description = "LOW低于下限，HIGH高于上限；上限0表示未设置")
    private String level;
    private BigDecimal difference;
}
