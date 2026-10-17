package cn.iocoder.yudao.module.mes.dal.mysql.qc.analysis;

import cn.iocoder.yudao.module.mes.dal.dataobject.qc.analysis.MesQcPeriodCount;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MesQcAnalysisMapper {
    @Select("""
        SELECT 'IQC' AS stage, COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN check_result <> #{pass} THEN 1 ELSE 0 END),0) AS failed
        FROM mes_qc_iqc WHERE deleted = FALSE AND status = #{finished}
          AND inspect_date >= #{start} AND inspect_date < #{end}
        UNION ALL
        SELECT 'IPQC' AS stage, COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN check_result <> #{pass} THEN 1 ELSE 0 END),0) AS failed
        FROM mes_qc_ipqc WHERE deleted = FALSE AND status = #{finished}
          AND inspect_date >= #{start} AND inspect_date < #{end}
        UNION ALL
        SELECT 'OQC' AS stage, COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN check_result <> #{pass} THEN 1 ELSE 0 END),0) AS failed
        FROM mes_qc_oqc WHERE deleted = FALSE AND status = #{finished}
          AND inspect_date >= #{start} AND inspect_date < #{end}
        """)
    List<MesQcPeriodCount> selectPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                      @Param("finished") Integer finished, @Param("pass") Integer pass);
}
