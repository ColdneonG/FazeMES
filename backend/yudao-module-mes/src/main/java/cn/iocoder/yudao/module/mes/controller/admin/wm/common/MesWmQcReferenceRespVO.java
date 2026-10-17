package cn.iocoder.yudao.module.mes.controller.admin.wm.common;

import lombok.Data;

/** 仓储选择已完成检验单时的最小参考信息。 */
@Data
public class MesWmQcReferenceRespVO {
    private Long id;
    private String code;
    private String name;
    private Integer checkResult;
}
