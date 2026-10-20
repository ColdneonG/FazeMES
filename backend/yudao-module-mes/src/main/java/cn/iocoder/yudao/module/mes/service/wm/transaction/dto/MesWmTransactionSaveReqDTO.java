package cn.iocoder.yudao.module.mes.service.wm.transaction.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MES 库存事务 创建 Request DTO
 */
@Data
@Accessors(chain = true)
public class MesWmTransactionSaveReqDTO {

    /** 出库必须扣减实际选中的库存记录，禁止按物料重新猜测批次或供应商。 */
    private Long materialStockId;
    /** 线边库存必须归属工单；实物仓交易为空。 */
    private Long workOrderId;

    // ===== 事务核心 =====

    /**
     * 事务类型
     *
     * 枚举 {@link cn.iocoder.yudao.module.mes.enums.wm.MesWmTransactionTypeEnum}
     */
    @NotNull(message = "事务类型不能为空")
    private Integer type;

    // ===== 业务来源 =====

    /**
     * 业务类型
     *
     * 对应 {@link cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants}
     */
    @NotNull(message = "业务类型不能为空")
    private Integer bizType;
    /**
     * 业务主单 ID
     */
    @NotNull(message = "业务主单 ID 不能为空")
    private Long bizId;
    /**
     * 业务单号
     */
    @NotNull(message = "业务单号不能为空")
    private String bizCode;
    /**
     * 业务行 ID
     */
    @NotNull(message = "业务行 ID 不能为空")
    private Long bizLineId;

    // ===== 物料维度 =====

    /**
     * 物料 ID
     */
    @NotNull(message = "物料 ID 不能为空")
    private Long itemId;
    /**
     * 变动数量（入库为正数，出库为负数）
     */
    @NotNull(message = "变动数量不能为空")
    private BigDecimal quantity;
    /**
     * 批次 ID
     */
    private Long batchId;
    /**
     * 批次号
     */
    private String batchCode;

    // ===== 库存位置 =====

    /**
     * 仓库 ID
     */
    @NotNull(message = "仓库 ID 不能为空")
    private Long warehouseId;
    /**
     * 库区 ID
     */
    @NotNull(message = "库区 ID 不能为空")
    private Long locationId;
    /**
     * 库位 ID
     */
    @NotNull(message = "库位 ID 不能为空")
    private Long areaId;

    // ===== 台账补充字段（入库时用于初始化 MesWmMaterialStockDO） =====

    /**
     * 供应商 ID（采购入库场景）
     */
    private Long vendorId;
    /**
     * 入库时间（为空默认当前时间）
     */
    private LocalDateTime receiptTime;

    // ===== 关联 =====

    /**
     * 关联事务 ID（成对事务中，入库事务关联出库事务）
     */
    private Long relatedTransactionId;
    /** Partial sales-return origin; deliberately distinct from equal-quantity paired movements. */
    private Long returnSourceTransactionId;
    /** Server-owned putaway row for 119, never an original sales detail guess. */
    private Long returnDetailId;

    // ===== 可选控制（非存储字段） =====

    /**
     * 是否校验库存充足（普通业务出库始终校验，不允许以 false 绕过）
     */
    private Boolean checkFlag;

}
