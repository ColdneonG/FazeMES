package cn.iocoder.yudao.module.mes.controller.admin.report;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.report.MesReportDefinitionDO;
import cn.iocoder.yudao.module.mes.service.report.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name="管理后台 - 安全业务报表设计")
@RestController @RequestMapping("/mes/report")
public class MesReportController {
    @Resource private MesReportService service;
    @Resource private MesReportDatasetRegistry registry;
    @Resource private SecurityFrameworkService security;
    /** Unknown executable/provider fields and malformed JSON are client errors, never server failures. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public CommonResult<?> invalidConfigurationBody() {
        var error=MesReportErrorConstants.invalid("报表配置格式无效或包含不支持的属性");
        return CommonResult.error(error.getCode(),error.getMessage());
    }
    private boolean canInspectDraft(){return security.hasAnyPermissions("mes:report:design","mes:report:publish");}
    @GetMapping("/datasets") @PreAuthorize("@ss.hasAnyPermissions('mes:report:query','mes:report:design','mes:report:publish')")
    public CommonResult<?> datasets(){return success(registry.list());}
    @GetMapping("/list") @PreAuthorize("@ss.hasAnyPermissions('mes:report:query','mes:report:design','mes:report:publish')")
    public CommonResult<List<MesReportDefinitionDO>> list(){return success(service.list(canInspectDraft()));}
    @GetMapping("/get") @PreAuthorize("@ss.hasAnyPermissions('mes:report:query','mes:report:design','mes:report:publish')")
    public CommonResult<?> get(@RequestParam Long id,@RequestParam(required=false) Integer version){return success(service.get(id,version,canInspectDraft()));}
    @PostMapping("/save") @PreAuthorize("@ss.hasPermission('mes:report:design')")
    public CommonResult<?> save(@Valid @RequestBody MesReportSaveReqVO req){return success(service.save(req,getLoginUserId()));}
    @PutMapping("/publish") @PreAuthorize("@ss.hasPermission('mes:report:publish')")
    public CommonResult<Boolean> publish(@RequestParam Long id,@RequestParam Integer version){service.publish(id,version,getLoginUserId());return success(true);}
    @PostMapping("/preview") @PreAuthorize("@ss.hasAnyPermissions('mes:report:query','mes:report:design','mes:report:publish')")
    public CommonResult<?> preview(@RequestBody MesReportPreviewReqVO req){return success(service.preview(req,security.hasPermission("mes:report:design"),false,canInspectDraft()));}
    @PostMapping("/export-excel") @PreAuthorize("@ss.hasPermission('mes:report:export')")
    @SuppressWarnings("unchecked")
    public void export(@RequestBody MesReportPreviewReqVO req,HttpServletResponse response) throws java.io.IOException {
        var data=service.preview(req,security.hasPermission("mes:report:design"),true,canInspectDraft());
        // Fail explicitly instead of silently producing an incomplete business report.
        if(Boolean.TRUE.equals(data.get("hasMore")))throw MesReportErrorConstants.invalid("导出超过5000行，请增加筛选条件后重试");
        var columns=(List<Map<String,Object>>)data.get("columns");var rows=(List<Map<String,Object>>)data.get("rows");
        try(var workbook=new XSSFWorkbook()) {
            var sheet=workbook.createSheet("业务报表");sheet.createFreezePane(0,1);var header=sheet.createRow(0);
            var style=workbook.createCellStyle();var font=workbook.createFont();font.setBold(true);style.setFont(font);
            for(int i=0;i<columns.size();i++){var cell=header.createCell(i);cell.setCellValue((String)columns.get(i).get("title"));cell.setCellStyle(style);sheet.setColumnWidth(i,Math.min(255*256,((Number)columns.get(i).get("width")).intValue()*36));}
            for(int n=0;n<rows.size();n++){var row=sheet.createRow(n+1);for(int i=0;i<columns.size();i++){Object v=rows.get(n).get(columns.get(i).get("key"));String field=(String)columns.get(i).get("field");var cell=row.createCell(i);boolean exactId="id".equals(field)||field.endsWith("_id");boolean large=v instanceof java.math.BigDecimal decimal&&decimal.precision()>15;if(v instanceof Number number&&!exactId&&!large)cell.setCellValue(number.doubleValue());else cell.setCellValue(v==null?"":v.toString());}}
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition","attachment; filename=mes-report.xlsx");workbook.write(response.getOutputStream());
        }
    }
}
