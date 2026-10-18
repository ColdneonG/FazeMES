package cn.iocoder.yudao.module.mes.service.pro.sop;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdItemMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.process.MesProProcessMapper;
import cn.iocoder.yudao.module.mes.enums.pro.MesProTaskStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.service.pro.sop.SopErrorConstants.*;

/** Controlled content is immutable after submission; executions always read the task copy. */
@Service
public class MesProSopServiceImpl implements MesProSopService {
    @Resource private MesSopDocumentMapper documentMapper;
    @Resource private MesSopVersionMapper versionMapper;
    @Resource private MesSopTaskSnapshotMapper snapshotMapper;
    @Resource private MesSopStepRecordMapper recordMapper;
    @Resource private MesProTaskMapper taskMapper;
    @Resource private MesProWorkOrderMapper workOrderMapper;
    @Resource private MesMdItemMapper itemMapper;
    @Resource private MesProProcessMapper processMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDocument(SopDocumentSaveReqVO req) {
        if (itemMapper.selectById(req.getItemId()) == null || processMapper.selectById(req.getProcessId()) == null) {
            throw exception(INVALID, "产品或工序不存在");
        }
        MesSopDocumentDO doc = new MesSopDocumentDO().setCode(req.getCode().trim()).setName(req.getName().trim())
                .setItemId(req.getItemId()).setProcessId(req.getProcessId());
        try { documentMapper.insert(doc); } catch (DuplicateKeyException ex) { throw exception(DUPLICATE); }
        return doc.getId();
    }

    @Override
    public List<MesSopDocumentDO> listDocuments(String keyword) {
        return documentMapper.selectList(new LambdaQueryWrapperX<MesSopDocumentDO>()
                .likeIfPresent(MesSopDocumentDO::getName, keyword).orderByDesc(MesSopDocumentDO::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveVersion(SopVersionSaveReqVO req) {
        if (documentMapper.lock(req.getDocumentId()) == null) throw exception(NOT_FOUND);
        validateContent(req);
        MesSopVersionDO version;
        if (req.getId() == null) {
            version = new MesSopVersionDO().setDocumentId(req.getDocumentId()).setStatus("DRAFT");
        } else {
            version = versionMapper.lock(req.getId());
            if (version == null || !Objects.equals(version.getDocumentId(), req.getDocumentId())) throw exception(NOT_FOUND);
            requireState(version, "DRAFT");
        }
        version.setVersionNo(req.getVersionNo().trim()).setTitle(req.getTitle().trim()).setChangeNote(req.getChangeNote())
                .setStepsJson(JsonUtils.toJsonString(req.getSteps())).setAttachmentsJson(JsonUtils.toJsonString(req.getAttachments()))
                .setModelUrl(req.getModelUrl() == null ? "" : req.getModelUrl());
        try {
            if (version.getId() == null) versionMapper.insert(version); else versionMapper.updateById(version);
        } catch (DuplicateKeyException ex) { throw exception(DUPLICATE); }
        return version.getId();
    }

    @Override
    public List<MesSopVersionDO> listVersions(Long documentId) {
        return versionMapper.selectList(new LambdaQueryWrapperX<MesSopVersionDO>()
                .eq(MesSopVersionDO::getDocumentId, documentId).orderByDesc(MesSopVersionDO::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transition(Long id, String action, String comment) {
        MesSopVersionDO before = versionMapper.selectById(id);
        if (before == null || documentMapper.lock(before.getDocumentId()) == null) throw exception(NOT_FOUND);
        MesSopVersionDO v = versionMapper.lock(id);
        LocalDateTime now = LocalDateTime.now();
        Long actor = actor();
        switch (action) {
            case "submit":
                requireState(v, "DRAFT");
                if (steps(v.getStepsJson()).isEmpty()) throw exception(INVALID, "至少一个步骤");
                v.setStatus("SUBMITTED").setSubmittedAt(now).setSubmittedBy(actor);
                break;
            case "approve":
            case "reject":
                requireState(v, "SUBMITTED");
                if ("reject".equals(action) && blank(comment)) throw exception(INVALID, "驳回原因必填");
                v.setStatus("approve".equals(action) ? "APPROVED" : "REJECTED")
                        .setReviewedAt(now).setReviewedBy(actor).setReviewComment(comment);
                break;
            case "publish":
                requireState(v, "APPROVED");
                // Document lock serializes publication with task binding and other releases.
                for (MesSopVersionDO old : listVersions(v.getDocumentId())) {
                    if ("PUBLISHED".equals(old.getStatus())) versionMapper.updateById(old.setStatus("SUPERSEDED"));
                }
                v.setStatus("PUBLISHED").setPublishedAt(now).setPublishedBy(actor);
                break;
            case "disable":
                if (!Set.of("PUBLISHED", "SUPERSEDED").contains(v.getStatus())) throw exception(STATE, v.getStatus());
                if (blank(comment)) throw exception(INVALID, "停用原因必填");
                v.setStatus("DISABLED").setDisabledAt(now).setDisabledBy(actor).setDisableReason(comment);
                break;
            default: throw exception(INVALID, "未知操作");
        }
        versionMapper.updateById(v);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SopTaskPackage lockForTask(MesProTaskDO task) {
        if (task == null || task.getId() == null) throw exception(NOT_FOUND);
        MesSopTaskSnapshotDO saved = snapshotMapper.findTask(task.getId());
        if (saved != null) {
            if (!Objects.equals(saved.getItemId(), task.getItemId()) || !Objects.equals(saved.getProcessId(), task.getProcessId())) {
                throw exception(INVALID, "已有SOP执行快照，不能更改任务产品或工序");
            }
            return packageOf(saved);
        }
        MesSopDocumentDO doc = documentMapper.findBinding(task.getItemId(), task.getProcessId(), true);
        if (doc == null) return new SopTaskPackage().setTaskId(task.getId());
        MesSopVersionDO v = versionMapper.selectOne(new LambdaQueryWrapperX<MesSopVersionDO>()
                .eq(MesSopVersionDO::getDocumentId, doc.getId()).eq(MesSopVersionDO::getStatus, "PUBLISHED").last("FOR UPDATE"));
        if (v == null) throw exception(NO_RELEASE);
        MesSopTaskSnapshotDO snapshot = new MesSopTaskSnapshotDO().setTaskId(task.getId()).setItemId(task.getItemId())
                .setProcessId(task.getProcessId()).setDocumentId(doc.getId())
                .setVersionId(v.getId()).setVersionNo(v.getVersionNo()).setTitle(v.getTitle()).setStepsJson(v.getStepsJson())
                .setAttachmentsJson(v.getAttachmentsJson()).setModelUrl(v.getModelUrl());
        snapshotMapper.insert(snapshot); // task lock + tenant/task unique key guarantee one immutable snapshot
        return packageOf(snapshot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SopTaskPackage getTaskPackage(Long taskId) {
        return lockForTask(lockTask(taskId, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateTaskExecution(Long taskId) {
        MesProTaskDO task = taskMapper.selectById(taskId);
        if (task == null) throw exception(NOT_FOUND);
        SopTaskPackage pkg = lockForTask(task);
        for (SopStep step : pkg.getSteps()) {
            if (Boolean.TRUE.equals(step.getRequired()) && pkg.getRecords().stream()
                    .noneMatch(r -> step.getId().equals(r.getStepId()) && r.getConfirmedAt() != null)) {
                throw exception(INCOMPLETE, step.getTitle());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void viewStep(SopExecutionReqVO req) {
        SopTaskPackage pkg = lockForTask(lockTask(req.getTaskId(), true));
        requireStep(pkg, req.getStepId());
        MesSopTaskSnapshotDO snapshot = snapshotMapper.findTask(req.getTaskId());
        if (recordMapper.findStep(snapshot.getId(), req.getStepId()) == null) {
            recordMapper.insert(new MesSopStepRecordDO().setSnapshotId(snapshot.getId()).setStepId(req.getStepId())
                    .setViewedAt(LocalDateTime.now()).setViewedBy(actor()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmStep(SopExecutionReqVO req) {
        SopTaskPackage pkg = lockForTask(lockTask(req.getTaskId(), true));
        SopStep step = requireStep(pkg, req.getStepId());
        MesSopTaskSnapshotDO snapshot = snapshotMapper.findTask(req.getTaskId());
        MesSopStepRecordDO record = recordMapper.findStep(snapshot.getId(), step.getId());
        if (record != null && record.getConfirmedAt() != null) return; // retry never overwrites original evidence
        if (record == null || record.getViewedAt() == null || Duration.between(record.getViewedAt(), LocalDateTime.now()).getSeconds() < step.getMinViewSeconds()) {
            throw exception(VIEW_TIME, step.getMinViewSeconds());
        }
        validateEvidence(step, req);
        record.setConfirmedAt(LocalDateTime.now()).setConfirmedBy(actor())
                .setParametersJson(JsonUtils.toJsonString(req.getParameters())).setPhotosJson(JsonUtils.toJsonString(req.getPhotos()));
        recordMapper.updateById(record);
    }

    private MesProTaskDO lockTask(Long id, boolean execution) {
        MesProTaskDO initial = taskMapper.selectById(id);
        if (initial == null) throw exception(NOT_FOUND);
        if (workOrderMapper.selectByIdForUpdate(initial.getWorkOrderId()) == null) throw exception(NOT_FOUND);
        MesProTaskDO task = taskMapper.selectByIdForUpdate(id);
        if (task == null || !Objects.equals(initial.getWorkOrderId(), task.getWorkOrderId())) throw exception(NOT_FOUND);
        if (execution && MesProTaskStatusEnum.isEndStatus(task.getStatus())) throw exception(STATE, "任务已结束");
        return task;
    }

    private SopTaskPackage packageOf(MesSopTaskSnapshotDO snapshot) {
        // Lock document then version, consistent with save/publication. Snapshot content never changes.
        documentMapper.lock(snapshot.getDocumentId());
        MesSopVersionDO v = versionMapper.lock(snapshot.getVersionId());
        if (v == null || "DISABLED".equals(v.getStatus())) throw exception(DISABLED);
        return new SopTaskPackage().setTaskId(snapshot.getTaskId()).setRequired(true).setDocumentId(snapshot.getDocumentId())
                .setVersionId(snapshot.getVersionId()).setVersionNo(snapshot.getVersionNo()).setTitle(snapshot.getTitle())
                .setModelUrl(snapshot.getModelUrl()).setAttachments(JsonUtils.parseArray(snapshot.getAttachmentsJson(), String.class))
                .setSteps(steps(snapshot.getStepsJson())).setRecords(recordMapper.selectList(MesSopStepRecordDO::getSnapshotId, snapshot.getId()));
    }

    private static List<SopStep> steps(String json) { return JsonUtils.parseArray(json, SopStep.class); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static Long actor() { Long id = getLoginUserId(); if (id == null) throw exception(INVALID, "需要登录身份"); return id; }
    private static void requireState(MesSopVersionDO v, String expected) {
        if (v == null) throw exception(NOT_FOUND);
        if (!expected.equals(v.getStatus())) throw exception(STATE, v.getStatus());
    }
    private static SopStep requireStep(SopTaskPackage pkg, String id) {
        return pkg.getSteps().stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow(() -> exception(NOT_FOUND));
    }

    static void validateEvidence(SopStep step, SopExecutionReqVO req) {
        if (req.getParameters() == null || req.getPhotos() == null) throw exception(INVALID, "参数及照片不能为空");
        validateUrls(req.getPhotos());
        if (Boolean.TRUE.equals(step.getPhotoRequired()) && req.getPhotos().isEmpty()) throw exception(INVALID, "此步骤必须上传照片");
        Set<String> allowed = new HashSet<>();
        for (SopStep.Parameter p : step.getParameters()) {
            allowed.add(p.getKey());
            BigDecimal value = req.getParameters().get(p.getKey());
            if (value == null) { if (Boolean.TRUE.equals(p.getRequired())) throw exception(INVALID, "缺少参数" + p.getLabel()); continue; }
            if ((p.getMin() != null && value.compareTo(p.getMin()) < 0) || (p.getMax() != null && value.compareTo(p.getMax()) > 0)) {
                throw exception(INVALID, "参数超出范围：" + p.getLabel());
            }
        }
        if (!allowed.containsAll(req.getParameters().keySet())) throw exception(INVALID, "包含未定义参数");
    }

    static void validateContent(SopVersionSaveReqVO req) {
        if (req.getSteps() == null || req.getSteps().isEmpty() || req.getSteps().size() > 200) throw exception(INVALID, "步骤数量1–200");
        validateUrls(req.getAttachments());
        if (!blank(req.getModelUrl())) {
            validateUrls(List.of(req.getModelUrl()));
            if (!req.getModelUrl().toLowerCase(Locale.ROOT).split("[?#]", 2)[0].endsWith(".glb")) throw exception(INVALID, "模型只支持GLB文件");
        }
        Set<String> ids = new HashSet<>();
        for (SopStep step : req.getSteps()) {
            if (step == null || blank(step.getTitle()) || step.getTitle().length() > 200 || blank(step.getInstruction())) throw exception(INVALID, "步骤标题和操作说明必填");
            if (blank(step.getId())) step.setId(UUID.randomUUID().toString());
            if (step.getId().length() > 64 || !ids.add(step.getId())) throw exception(INVALID, "步骤标识重复或过长");
            if (step.getRequired() == null || step.getPhotoRequired() == null || step.getMinViewSeconds() == null || step.getMinViewSeconds() < 0 || step.getMinViewSeconds() > 86400) throw exception(INVALID, "查看时长范围0–86400秒");
            validateUrls(step.getAttachments());
            if (step.getParameters() == null || step.getParameters().size() > 50) throw exception(INVALID, "参数最多50项");
            Set<String> keys = new HashSet<>();
            for (SopStep.Parameter p : step.getParameters()) {
                if (p == null || blank(p.getKey()) || !p.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || !keys.add(p.getKey()) || blank(p.getLabel()) || p.getRequired() == null) throw exception(INVALID, "参数名称或标识无效/重复");
                if (p.getMin() != null && p.getMax() != null && p.getMin().compareTo(p.getMax()) > 0) throw exception(INVALID, "参数下限不能大于上限");
            }
        }
    }

    private static void validateUrls(List<String> urls) {
        if (urls == null || urls.size() > 50) throw exception(INVALID, "附件数量0–50");
        for (String url : urls) {
            if (blank(url) || url.length() > 2048 || !(url.startsWith("https://") || url.startsWith("http://") || (url.startsWith("/") && !url.startsWith("//")))) throw exception(INVALID, "附件必须为上传后的HTTP(S)或站内文件地址");
        }
    }
}
