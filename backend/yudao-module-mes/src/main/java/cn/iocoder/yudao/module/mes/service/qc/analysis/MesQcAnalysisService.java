package cn.iocoder.yudao.module.mes.service.qc.analysis;

import cn.iocoder.yudao.module.mes.controller.admin.qc.analysis.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.analysis.MesQcPeriodCount;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.analysis.MesQcAnalysisMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.QC_ANALYSIS_PERIOD_INVALID;

@Service
public class MesQcAnalysisService {
    @Resource
    private MesQcAnalysisMapper mapper;

    public MesQcComparisonRespVO compare(MesQcComparisonReqVO req) {
        if (req.getStartDate() == null || req.getEndDate() == null
                || req.getEndDate().isBefore(req.getStartDate())
                || ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) >= 366
                || req.getCompareMode() == null || !List.of("YEAR_AGO", "PREVIOUS").contains(req.getCompareMode())) {
            throw exception(QC_ANALYSIS_PERIOD_INVALID);
        }
        validateThreshold(req.getRateThreshold());
        validateThreshold(req.getIncreaseThreshold());
        long days = ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1;
        LocalDate beforeStart = "YEAR_AGO".equals(req.getCompareMode()) ? req.getStartDate().minusYears(1) : req.getStartDate().minusDays(days);
        LocalDate beforeEnd = "YEAR_AGO".equals(req.getCompareMode()) ? req.getEndDate().minusYears(1) : req.getStartDate().minusDays(1);
        Map<String, MesQcPeriodCount> current = load(req.getStartDate(), req.getEndDate());
        Map<String, MesQcPeriodCount> previous = load(beforeStart, beforeEnd);
        MesQcComparisonRespVO result = new MesQcComparisonRespVO();
        result.setStartDate(req.getStartDate()); result.setEndDate(req.getEndDate());
        result.setComparisonStartDate(beforeStart); result.setComparisonEndDate(beforeEnd);
        result.setRows(List.of("IQC", "IPQC", "OQC").stream().map(stage -> {
            MesQcPeriodCount now = current.getOrDefault(stage, new MesQcPeriodCount());
            MesQcPeriodCount old = previous.getOrDefault(stage, new MesQcPeriodCount());
            MesQcComparisonRespVO.Row row = new MesQcComparisonRespVO.Row();
            row.setStage(stage); row.setCurrentTotal(now.getTotal()); row.setCurrentFailed(now.getFailed());
            row.setComparisonTotal(old.getTotal()); row.setComparisonFailed(old.getFailed());
            row.setCurrentRate(rate(now)); row.setComparisonRate(rate(old));
            if (row.getCurrentRate() != null && row.getComparisonRate() != null) {
                row.setChangePoints(row.getCurrentRate().subtract(row.getComparisonRate()));
            }
            boolean high = row.getCurrentRate() != null && row.getCurrentRate().compareTo(req.getRateThreshold()) > 0;
            boolean worse = row.getChangePoints() != null && row.getChangePoints().compareTo(req.getIncreaseThreshold()) > 0;
            row.setAlert(high || worse);
            row.setMessage(now.getTotal() == 0 ? "本期无已完成检验单" : high && worse ? "异常率超阈值且较对比期上升"
                    : high ? "异常率超阈值" : worse ? "异常率较对比期上升" : old.getTotal() == 0 ? "对比期无数据，仅判断本期阈值" : "正常");
            return row;
        }).toList());
        return result;
    }

    private Map<String, MesQcPeriodCount> load(LocalDate start, LocalDate end) {
        Map<String, MesQcPeriodCount> result = new HashMap<>();
        mapper.selectPeriod(start.atStartOfDay(), end.plusDays(1).atStartOfDay(), MesQcStatusEnum.FINISHED.getStatus(), MesQcCheckResultEnum.PASS.getType())
                .forEach(row -> result.put(row.getStage(), row));
        return result;
    }

    private BigDecimal rate(MesQcPeriodCount count) {
        return count.getTotal() == 0 ? null : BigDecimal.valueOf(count.getFailed()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(count.getTotal()), 4, RoundingMode.HALF_UP);
    }

    private void validateThreshold(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("预警阈值必须在0至100之间");
        }
    }
}
