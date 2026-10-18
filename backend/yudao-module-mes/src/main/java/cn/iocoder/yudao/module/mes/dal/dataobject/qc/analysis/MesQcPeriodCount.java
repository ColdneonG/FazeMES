package cn.iocoder.yudao.module.mes.dal.dataobject.qc.analysis;

import lombok.Data;

/** 按已完成检验单批次统计，避免把千克、件等不同单位的数量混加。 */
@Data
public class MesQcPeriodCount {
    private String stage;
    private long total;
    private long failed;
}
