package cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class MesProSnTraceRegisterReqVO {
    @NotNull @Positive private Long workOrderId;
    @Positive private Long snId;
    @Size(max = 128) private String snCode;
}
