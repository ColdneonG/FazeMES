package cn.iocoder.yudao.module.mes.controller.admin.qc.analysis;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.controller.admin.qc.analysis.vo.*;
import cn.iocoder.yudao.module.mes.service.qc.analysis.MesQcAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/qc/analysis")
@Tag(name = "管理后台 - MES 质量同期比较")
public class MesQcAnalysisController {
    @Resource
    private MesQcAnalysisService service;

    @GetMapping("/comparison")
    @Operation(summary = "按已完成检验单的批次异常率进行同比或上一周期比较并返回预警")
    @PreAuthorize("@ss.hasPermission('mes:qc-iqc:query') and @ss.hasPermission('mes:qc-ipqc:query') and @ss.hasPermission('mes:qc-oqc:query')")
    public CommonResult<MesQcComparisonRespVO> compare(@Valid MesQcComparisonReqVO reqVO) {
        return success(service.compare(reqVO));
    }
}
