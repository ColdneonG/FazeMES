package cn.iocoder.yudao.module.mes.controller.admin.cost.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** All monetary values are CNY, exclusive of tax. Null always means unavailable. */
public final class MesCostVO {
    private MesCostVO() {}
    public record ChildSettlement(long id, String code, boolean settled) {}
    @Data public static class Profile {
        private Long id;
        @NotNull private Long itemId;
        private Integer version;
        @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal purchasePrice;
        @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal salePrice;
        @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal laborCost;
        @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal overheadCost;
        @Size(max=500) private String remark;
        private LocalDateTime createTime;
    }
    @Data public static class ProductCost {
        private Long itemId;
        private String itemCode;
        private String itemName;
        private String unitMeasureName;
        private String currency = "CNY";
        private String taxBasis = "EXCLUSIVE";
        private String status = "COMPLETE";
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity = BigDecimal.ONE;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal purchasePrice;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal salePrice;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal materialCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal laborCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal overheadCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal totalCost;
        private Long priceVersionId;
        private List<ProductCost> children = new ArrayList<>();
        private List<Leaf> leaves = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
    }
    @Data public static class Leaf {
        private Long itemId;
        private String itemCode;
        private String itemName;
        private String unitMeasureName;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal purchasePrice;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal amount;
    }
    @Data public static class LinePrice {
        @NotNull private Long lineId;
        @NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal unitPrice;
        @Size(max=500) private String remark;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal amount;
        private Boolean locked;
    }
    @Data public static class Effort {
        private Long id;
        @NotNull private Long workorderId;
        @NotNull private Long processId;
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=4) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal hours;
        @NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal hourlyRate;
        @NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal overheadAmount;
        @NotBlank @Size(max=500) private String remark;
        /** Client-generated UUID; a retry cannot double-book hours. */
        @NotBlank @Size(max=64) private String requestId;
        private LocalDateTime createTime;
        private Boolean voided;
        private String voidReason;
        private LocalDateTime voidTime;
        private String voidBy;
    }
    @Data public static class VoidEffort {
        @NotNull private Long id;
        @NotBlank @Size(max=500) private String reason;
    }
    @Data public static class LossAllocation {
        @NotNull private Long workorderId;
        @NotBlank @Size(max=500) private String reason;
    }
    @Data public static class WorkorderCost {
        private Long workorderId;
        private String status;
        private ProductCost planSnapshot;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal planQuantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal planMaterialCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal planLaborCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal planOverheadCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal planTotalCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal totalVariance;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal unitVariance;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal actualMaterialCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal actualLaborCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal actualOverheadCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal actualTotalCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal producedQuantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal actualUnitCost;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal lossQuantity;
        private String lossAllocationMethod;
        private String lossAllocationReason;
        private Long lossAllocatedBy;
        @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") private LocalDateTime lossAllocatedAt;
        private List<String> issues = new ArrayList<>();
        private List<Effort> efforts = new ArrayList<>();
    }
    @Data public static class SaleLine {
        private Long lineId;
        private Long itemId;
        private String itemCode;
        private String itemName;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal unitPrice;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal salesAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal costAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal grossProfit;
        /** Ratio, not percentage; null for zero revenue. */
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal grossMargin;
        private String status;
        private List<String> issues = new ArrayList<>();
    }
    @Data public static class Sale {
        private Long salesId;
        private String status;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal salesAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal costAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal grossProfit;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal grossMargin;
        private List<SaleLine> lines = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
    }
    @Data public static class ReturnSaleLine {
        private Long lineId;
        private Long itemId;
        private String itemCode;
        private String itemName;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal quantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal postedQuantity;
        private Long originTransactionId;
        private Long originSalesId;
        private String originSalesCode;
        private Long originSalesLineId;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal materialAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal laborAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal overheadAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal costAmount;
        private String status;
        private List<String> issues = new ArrayList<>();
    }
    @Data public static class ReturnSaleOriginal {
        private Long originTransactionId;
        private Long salesId;
        private String salesCode;
        private Long salesLineId;
        private Long itemId;
        private String itemCode;
        private String itemName;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal outboundQuantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal returnedQuantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal remainingQuantity;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal originalCostAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal returnedCostAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal remainingCostAmount;
        private String status;
        private List<String> issues = new ArrayList<>();
    }
    @Data public static class ReturnSale {
        private Long returnId;
        private String returnCode;
        private String status;
        private Boolean posted;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal materialAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal laborAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal overheadAmount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) private BigDecimal costAmount;
        private List<ReturnSaleLine> lines = new ArrayList<>();
        private List<ReturnSaleOriginal> originals = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
    }
}
