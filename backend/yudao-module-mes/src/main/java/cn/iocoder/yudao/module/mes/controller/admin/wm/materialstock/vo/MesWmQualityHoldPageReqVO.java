package cn.iocoder.yudao.module.mes.controller.admin.wm.materialstock.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "历史质量待核对证据分页")
public class MesWmQualityHoldPageReqVO extends PageParam {
    @Schema(description = "具体库存记录；不传则查看本租户全部含无法定位的异常")
    private Long materialStockId;
}
