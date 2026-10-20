package cn.iocoder.yudao.module.mes.controller.admin.insight;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.mes.service.insight.MesInsightService;
import cn.iocoder.yudao.module.mes.service.insight.MesInsightBusinessAssistant;
import cn.iocoder.yudao.module.mes.service.insight.MesInsightConversationStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/mes/insight")
@Validated
public class MesInsightController {
    private final MesInsightService service;
    private final MesInsightBusinessAssistant business;
    private final MesInsightConversationStore conversations;
    public MesInsightController(MesInsightService service, MesInsightBusinessAssistant business, MesInsightConversationStore conversations) { this.service = service; this.business=business; this.conversations=conversations; }
    public record Simulation(@NotNull Long workOrderId, @Min(8) @Max(12) int dailyHours,
                             @DecimalMin("0") @DecimalMax("100000") double hourlyCost,
                             Long workstationId, @DecimalMin("0.1") @DecimalMax("1000") double manualHours) {}
    public record IncidentCreate(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=30) String category,
                                 Long workOrderId, Long workstationId, Long andonId, Long ownerId,
                                 LocalDateTime dueAt, @Size(max=2000) String description,
                                 Long departmentId, Long triageId) {
        public IncidentCreate(String title,String category,Long workOrderId,Long workstationId,Long andonId,Long ownerId,LocalDateTime dueAt,String description) {
            this(title,category,workOrderId,workstationId,andonId,ownerId,dueAt,description,null,null);
        }
    }
    public record IncidentMove(@NotNull Long id, @NotNull @Min(0) Integer version, @NotBlank String status,
                               Long ownerId, LocalDateTime dueAt, @Size(max=2000) String rootCause,
                               @Size(max=2000) String correctiveAction, @Size(max=2000) String evidence,
                               @NotBlank @Size(max=2000) String note) {}
    public record ClarificationChoice(@NotBlank @Size(max=64) String token,@NotBlank @Size(max=64) String choiceId) {}
    public record Question(@Size(max=1000) String question, boolean useModel, Long conversationId,
                           @Min(0) Integer version,@Valid ClarificationChoice clarification) {
        public Question(String question,boolean useModel){this(question,useModel,null,null,null);}
    }
    public record ConversationCreate(@Size(max=160) String title) {}
    public record DraftCreate(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=4000) String content,
                              Long workOrderId) {}

    @GetMapping("/risks") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Map<String,Object>> risks() { return success(service.risks()); }
    @PostMapping("/simulate") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:simulate')")
    public CommonResult<Map<String,Object>> simulate(@Valid @RequestBody Simulation req) { return success(service.simulate(req)); }
    @GetMapping("/quality") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Map<String,Object>> quality(@RequestParam(defaultValue="30") @Min(1) @Max(365) int days,
                                                   @RequestParam(defaultValue="30") @Min(1) @Max(100000) int minSample) {
        return success(service.quality(days, minSample));
    }
    @GetMapping("/flow") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Map<String,Object>> flow(@RequestParam(defaultValue="30") @Min(1) @Max(365) int days) {
        return success(service.flow(days));
    }
    @GetMapping("/timeline") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Map<String,Object>> timeline(@RequestParam Long workOrderId) { return success(service.timeline(workOrderId)); }
    @GetMapping("/incidents") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Map<String,Object>> incidents() { return success(service.incidents()); }
    @GetMapping("/incident-history") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Object> history(@RequestParam Long id) { return success(service.history(id)); }
    @PostMapping("/incident") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')")
    public CommonResult<Long> create(@Valid @RequestBody IncidentCreate req) { return success(service.createIncident(req)); }
    @PutMapping("/incident") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')")
    public CommonResult<Boolean> move(@Valid @RequestBody IncidentMove req) { service.moveIncident(req); return success(true); }
    @PostMapping("/assistant") @ApiAccessLog(requestEnable=false)
    @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Map<String,Object>> assistant(@Valid @RequestBody Question req) { return success(business.ask(req)); }
    @GetMapping("/conversation/page") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Map<String,Object>> conversationPage(@RequestParam(defaultValue="1") @Min(1) int pageNo,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize){return success(conversations.page(pageNo,pageSize));}
    @PostMapping("/conversation/create") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Map<String,Object>> conversationCreate(@Valid @RequestBody ConversationCreate req){return success(conversations.create(req.title()));}
    @GetMapping("/conversation/get") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Map<String,Object>> conversationGet(@RequestParam Long id,@RequestParam(required=false) Long beforeTurnId,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize){return success(conversations.get(id,beforeTurnId,pageSize));}
    @DeleteMapping("/conversation/delete") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Boolean> conversationDelete(@RequestParam Long id,@RequestParam @Min(0) int version){conversations.delete(id,version);return success(true);}
    @GetMapping("/conversation/evidence") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:chat')")
    public CommonResult<Map<String,Object>> conversationEvidence(@RequestParam Long turnId,@RequestParam(defaultValue="1") @Min(1) int pageNo,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize){return success(conversations.evidence(turnId,pageNo,pageSize));}
    @GetMapping("/drafts") @PreAuthorize("@ss.hasPermission('mes:insight:query')")
    public CommonResult<Object> drafts() { return success(service.drafts()); }
    @PostMapping("/draft") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')")
    public CommonResult<Long> draft(@Valid @RequestBody DraftCreate req) { return success(service.saveDraft(req)); }
    @PostMapping("/draft-convert") @PreAuthorize("@ss.hasPermission('mes:insight:query') && @ss.hasPermission('mes:insight:manage')")
    public CommonResult<Long> convert(@RequestParam Long id) { return success(service.convertDraft(id)); }
}
