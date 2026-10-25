package cn.iocoder.yudao.module.mes.controller.admin.insight;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.mes.service.insight.MesAgentBusinessTools;
import cn.iocoder.yudao.module.mes.service.insight.MesInsightConversationStore;
import cn.idev.excel.annotation.ExcelProperty;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.invalid;

@RestController
@RequestMapping("/mes/insight/agent")
@PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
public class MesAgentBusinessController {
    private final MesAgentBusinessTools tools;
    private final MesInsightConversationStore conversations;
    public MesAgentBusinessController(MesAgentBusinessTools tools,MesInsightConversationStore conversations){this.tools=tools;this.conversations=conversations;}
    @PostMapping("/prepare") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat') && @ss.hasPermission('mes:insight:manage')")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<?> prepare(@RequestParam long turnId,@RequestBody MesAgentBusinessTools.Operation request){return success(tools.prepare(request,turnId));}
    @PostMapping("/execute") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat') && @ss.hasPermission('mes:insight:manage')")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<?> execute(@RequestBody MesAgentBusinessTools.Confirmation request){return success(tools.execute(request));}
    @GetMapping("/report")
    @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat') && @ss.hasPermission('mes:pro-work-order:query') && @ss.hasPermission('mes:pro-work-order:export')")
    @SuppressWarnings("unchecked")
    public void report(@RequestParam long turnId,HttpServletResponse response) throws IOException {
        var evidence=conversations.artifact(turnId);
        if(!"AGENT_TABLE".equals(evidence.get("kind"))||!"WORKORDER".equals(evidence.get("module")))throw invalid("此轮答复没有工单报表");
        var rows=(List<Map<String,Object>>)evidence.getOrDefault("list",List.of());
        if(rows.size()>5000)throw invalid("报表超过5000行，请缩小范围");
        var output=new ArrayList<WorkOrderRow>();
        for(var row:rows){var item=new WorkOrderRow();item.id=text(row.get("id"));item.code=text(row.get("code"));item.name=text(row.get("name"));item.product=text(row.get("productName"));
            item.quantity=text(row.get("quantity"));item.produced=text(row.get("producedQuantity"));item.status=text(row.get("statusName"));item.dueAt=text(row.get("dueAt"));item.createdAt=text(row.get("createdAt"));item.asOf=text(evidence.get("asOf"));item.scope=text(evidence.get("basis"));output.add(item);}
        ExcelUtils.write(response,"生产工单报表-"+turnId+".xlsx","工单快照",WorkOrderRow.class,output);
    }
    private static String text(Object value){return value==null?"":value.toString();}
    @Data public static class WorkOrderRow {
        @ExcelProperty("记录编号") private String id;
        @ExcelProperty("工单编码") private String code;
        @ExcelProperty("工单名称") private String name;
        @ExcelProperty("产品") private String product;
        @ExcelProperty("计划数量") private String quantity;
        @ExcelProperty("完成数量") private String produced;
        @ExcelProperty("状态") private String status;
        @ExcelProperty("需求日期") private String dueAt;
        @ExcelProperty("创建时间") private String createdAt;
        @ExcelProperty("查询快照时间") private String asOf;
        @ExcelProperty("查询范围") private String scope;
    }
}
