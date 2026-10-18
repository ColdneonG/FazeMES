package cn.iocoder.yudao.module.mes.controller.admin.assistant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.mes.service.assistant.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - AI助手与RAG知识库")
@RestController
@RequestMapping("/mes/assistant")
public class MesAssistantController {
    private final MesAssistantService service;
    private final MesAssistantModelGateway models;
    private final SecurityFrameworkService security;
    public MesAssistantController(MesAssistantService service, MesAssistantModelGateway models, SecurityFrameworkService security) {
        this.service = service; this.models = models; this.security = security;
    }
    private boolean manage() { return security.hasPermission("mes:assistant:manage"); }
    private static long user() { Long id = getLoginUserId(); if (id == null) throw MesAssistantErrors.missing(); return id; }
    public record BaseSave(Long id, @NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {}
    public record Search(@NotBlank @Size(max = 1000) String query, @Size(max = 20) List<Long> knowledgeBaseIds, @Min(1) @Max(10) Integer limit) {}
    public record SessionTitle(@Size(max = 100) String title) {}
    public record Message(@NotNull Long sessionId, @NotBlank @Size(max = 4000) String question, @Size(max = 20) List<Long> knowledgeBaseIds) {}

    @GetMapping("/status") @PreAuthorize("@ss.hasPermission('mes:assistant:query')")
    public CommonResult<?> status() { return success(models.status()); }
    @GetMapping("/bases") @PreAuthorize("@ss.hasAnyPermissions('mes:assistant:knowledge','mes:assistant:manage')")
    public CommonResult<?> bases() { return success(service.bases(manage())); }
    @PostMapping("/base") @PreAuthorize("@ss.hasPermission('mes:assistant:manage')")
    public CommonResult<?> saveBase(@Valid @RequestBody BaseSave body) { return success(service.saveBase(body.id(), body.name(), body.description(), user())); }
    @DeleteMapping("/base") @PreAuthorize("@ss.hasPermission('mes:assistant:manage')")
    public CommonResult<?> deleteBase(@RequestParam long id) { service.deleteBase(id); return success(true); }
    @GetMapping("/documents") @PreAuthorize("@ss.hasAnyPermissions('mes:assistant:knowledge','mes:assistant:manage')")
    public CommonResult<?> documents(@RequestParam long knowledgeBaseId) { return success(service.documents(knowledgeBaseId, manage())); }
    @PostMapping(value = "/document/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermission('mes:assistant:manage')") @ApiAccessLog(requestEnable = false)
    public CommonResult<?> upload(@RequestParam long knowledgeBaseId, @RequestParam(required = false) String title,
                                   @RequestPart("file") MultipartFile file) throws java.io.IOException {
        if (file.isEmpty() || file.getSize() > MesAssistantDocumentParser.MAX_FILE_BYTES) throw MesAssistantErrors.invalid("文件为空或超过5MB");
        return success(service.upload(knowledgeBaseId, title, file.getOriginalFilename(), file.getBytes(), user()));
    }
    @GetMapping("/document") @PreAuthorize("@ss.hasAnyPermissions('mes:assistant:knowledge','mes:assistant:manage')")
    public CommonResult<?> document(@RequestParam long id) { return success(service.document(id, manage())); }
    @GetMapping("/document/download") @PreAuthorize("@ss.hasAnyPermissions('mes:assistant:knowledge','mes:assistant:manage')")
    public ResponseEntity<byte[]> download(@RequestParam long id) {
        var file = service.download(id, manage());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
    @PutMapping("/document/publish") @PreAuthorize("@ss.hasPermission('mes:assistant:manage')")
    public CommonResult<?> publish(@RequestParam long id, @RequestParam boolean published) { service.publish(id, published); return success(true); }
    @DeleteMapping("/document") @PreAuthorize("@ss.hasPermission('mes:assistant:manage')")
    public CommonResult<?> deleteDocument(@RequestParam long id) { service.deleteDocument(id); return success(true); }
    @PostMapping("/search") @PreAuthorize("@ss.hasPermission('mes:assistant:knowledge')") @ApiAccessLog(requestEnable = false)
    public CommonResult<?> search(@Valid @RequestBody Search body) { return success(service.search(body.query(), body.knowledgeBaseIds(), body.limit() == null ? 5 : body.limit())); }

    @GetMapping("/sessions") @PreAuthorize("@ss.hasPermission('mes:assistant:chat')")
    public CommonResult<?> sessions() { return success(service.sessions(user())); }
    @PostMapping("/session") @PreAuthorize("@ss.hasPermission('mes:assistant:chat')") @ApiAccessLog(requestEnable = false)
    public CommonResult<?> createSession(@Valid @RequestBody SessionTitle body) { return success(service.createSession(body.title(), user())); }
    @GetMapping("/session") @PreAuthorize("@ss.hasPermission('mes:assistant:chat')")
    public CommonResult<?> session(@RequestParam long id) { return success(service.session(id, user())); }
    @PutMapping("/session") @PreAuthorize("@ss.hasPermission('mes:assistant:chat')") @ApiAccessLog(requestEnable = false)
    public CommonResult<?> renameSession(@RequestParam long id, @Valid @RequestBody SessionTitle body) { service.renameSession(id, body.title(), user()); return success(true); }
    @DeleteMapping("/session") @PreAuthorize("@ss.hasPermission('mes:assistant:chat')")
    public CommonResult<?> deleteSession(@RequestParam long id) { service.deleteSession(id, user()); return success(true); }
    @PostMapping("/message")
    @PreAuthorize("@ss.hasPermission('mes:assistant:chat') and @ss.hasPermission('mes:assistant:knowledge')")
    @ApiAccessLog(requestEnable = false)
    public CommonResult<?> message(@Valid @RequestBody Message body) { return success(service.send(body.sessionId(), body.question(), body.knowledgeBaseIds(), user())); }

    @GetMapping("/model-config") @PreAuthorize("@ss.hasPermission('mes:assistant:config')")
    public CommonResult<?> modelConfig() { return success(models.settings()); }
    @PutMapping("/model-config") @PreAuthorize("@ss.hasPermission('mes:assistant:config')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, sanitizeKeys = {"apiKey"})
    public CommonResult<?> saveModelConfig(@RequestBody MesAssistantModelGateway.Settings body) { return success(models.save(body, user())); }
    @DeleteMapping("/model-config") @PreAuthorize("@ss.hasPermission('mes:assistant:config')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<?> resetModelConfig() { models.reset(); return success(true); }
    @PostMapping("/model-config/test") @PreAuthorize("@ss.hasPermission('mes:assistant:config')") @ApiAccessLog(requestEnable = false)
    public CommonResult<?> testModelConfig() { return success(models.testConnection()); }
}
