package cn.iocoder.yudao.module.mes.dal.mysql.pro.kitting;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class KittingStockRow {
    private Long id;
    private Long itemId;
    private Long areaId;
    private Long warehouseId;
    private Long locationId;
    private Long batchId;
    private String batchCode;
    private String warehouseName;
    private String locationName;
    private String areaName;
    private BigDecimal quantity;
    private LocalDateTime receiptTime;
    private boolean eligible;
}
