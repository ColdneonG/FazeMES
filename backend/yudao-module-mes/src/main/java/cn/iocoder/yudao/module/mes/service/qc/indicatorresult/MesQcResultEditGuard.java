package cn.iocoder.yudao.module.mes.service.qc.indicatorresult;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.iqc.MesQcIqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.ipqc.MesQcIpqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.oqc.MesQcOqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.iqc.MesQcIqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.ipqc.MesQcIpqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.oqc.MesQcOqcMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.Objects;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/** Lock the inspection header until result writes commit, excluding concurrent completion. */
@Component
public class MesQcResultEditGuard {
    @Resource private MesQcIqcMapper iqcMapper;
    @Resource private MesQcIpqcMapper ipqcMapper;
    @Resource private MesQcOqcMapper oqcMapper;
    @Resource private MesQcRqcMapper rqcMapper;

    public void requireDraft(Long id, Integer type) {
        Integer status;
        if (Objects.equals(type, MesQcTypeEnum.IQC.getType())) {
            MesQcIqcDO row=iqcMapper.selectOne(new LambdaQueryWrapperX<MesQcIqcDO>().eq(MesQcIqcDO::getId,id).last("FOR UPDATE"));
            if(row==null) throw exception(QC_IQC_NOT_EXISTS);
            status=row.getStatus();
        } else if (Objects.equals(type, MesQcTypeEnum.IPQC.getType())) {
            MesQcIpqcDO row=ipqcMapper.selectOne(new LambdaQueryWrapperX<MesQcIpqcDO>().eq(MesQcIpqcDO::getId,id).last("FOR UPDATE"));
            if(row==null) throw exception(QC_IPQC_NOT_EXISTS);
            status=row.getStatus();
        } else if (Objects.equals(type, MesQcTypeEnum.OQC.getType())) {
            MesQcOqcDO row=oqcMapper.selectOne(new LambdaQueryWrapperX<MesQcOqcDO>().eq(MesQcOqcDO::getId,id).last("FOR UPDATE"));
            if(row==null) throw exception(QC_OQC_NOT_EXISTS);
            status=row.getStatus();
        } else if (Objects.equals(type, MesQcTypeEnum.RQC.getType())) {
            MesQcRqcDO row=rqcMapper.selectOne(new LambdaQueryWrapperX<MesQcRqcDO>().eq(MesQcRqcDO::getId,id).last("FOR UPDATE"));
            if(row==null) throw exception(QC_RQC_NOT_EXISTS);
            status=row.getStatus();
        } else {
            throw exception(QC_RESULT_READ_ONLY);
        }
        if (!Objects.equals(status, MesQcStatusEnum.DRAFT.getStatus())) throw exception(QC_RESULT_READ_ONLY);
    }
}
