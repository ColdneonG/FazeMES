package cn.iocoder.yudao.module.mes.service.pro.sop;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sop.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sop.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sop.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdItemMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.process.MesProProcessMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MesProSopServiceImplTest {
    @InjectMocks private MesProSopServiceImpl service;
    @Mock private MesSopDocumentMapper documentMapper;
    @Mock private MesSopVersionMapper versionMapper;
    @Mock private MesSopTaskSnapshotMapper snapshotMapper;
    @Mock private MesSopStepRecordMapper recordMapper;
    @Mock private MesProTaskMapper taskMapper;
    @Mock private MesProWorkOrderMapper workOrderMapper;
    @Mock private MesMdItemMapper itemMapper;
    @Mock private MesProProcessMapper processMapper;
    private MesProTaskDO task;
    private MesSopVersionDO version;
    private MesSopTaskSnapshotDO snapshot;
    private SopStep step;

    @BeforeEach
    void setup() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(77L).setUserType(2), new MockHttpServletRequest());
        task = new MesProTaskDO().setId(10L).setWorkOrderId(20L).setItemId(30L).setProcessId(40L).setStatus(0);
        step = new SopStep().setId("tighten").setTitle("拧紧").setInstruction("按要求拧紧").setMinViewSeconds(5);
        String json = JsonUtils.toJsonString(List.of(step));
        version = new MesSopVersionDO().setId(2L).setDocumentId(1L).setVersionNo("A").setTitle("作业规程")
                .setStatus("PUBLISHED").setStepsJson(json).setAttachmentsJson("[]").setModelUrl("");
        snapshot = new MesSopTaskSnapshotDO().setId(3L).setTaskId(10L).setItemId(30L).setProcessId(40L).setDocumentId(1L).setVersionId(2L)
                .setVersionNo("A").setTitle("作业规程").setStepsJson(json).setAttachmentsJson("[]").setModelUrl("");
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(taskMapper.selectByIdForUpdate(10L)).thenReturn(task);
        when(workOrderMapper.selectByIdForUpdate(20L)).thenReturn(new MesProWorkOrderDO().setId(20L));
        when(documentMapper.lock(1L)).thenReturn(new MesSopDocumentDO().setId(1L));
        when(versionMapper.lock(2L)).thenReturn(version);
        when(recordMapper.selectList(any(SFunction.class), eq(3L))).thenReturn(new ArrayList<>());
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void noDocumentDoesNotPersistPermanentExemption() {
        SopTaskPackage pkg = service.lockForTask(task);
        assertFalse(pkg.getRequired());
        verify(snapshotMapper, never()).insert(any(MesSopTaskSnapshotDO.class));
    }

    @Test
    void configuredDocumentWithoutReleaseBlocksTask() {
        when(documentMapper.findBinding(30L, 40L, true)).thenReturn(new MesSopDocumentDO().setId(1L));
        assertCode(SopErrorConstants.NO_RELEASE.getCode(), () -> service.lockForTask(task));
        verify(snapshotMapper, never()).insert(any(MesSopTaskSnapshotDO.class));
    }

    @Test
    void firstBindingCopiesContentAndExistingTaskNeverFollowsNewRelease() {
        when(documentMapper.findBinding(30L, 40L, true)).thenReturn(new MesSopDocumentDO().setId(1L));
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(version);
        doAnswer(invocation -> { invocation.<MesSopTaskSnapshotDO>getArgument(0).setId(3L); return 1; })
                .when(snapshotMapper).insert(any(MesSopTaskSnapshotDO.class));
        SopTaskPackage first = service.lockForTask(task);
        assertEquals("A", first.getVersionNo());
        ArgumentCaptor<MesSopTaskSnapshotDO> captured = ArgumentCaptor.forClass(MesSopTaskSnapshotDO.class);
        verify(snapshotMapper).insert(captured.capture());
        when(snapshotMapper.findTask(10L)).thenReturn(captured.getValue());
        version.setStatus("SUPERSEDED").setTitle("后来标题").setStepsJson("[]");
        SopTaskPackage pinned = service.lockForTask(task);
        assertEquals("作业规程", pinned.getTitle());
        assertEquals("拧紧", pinned.getSteps().get(0).getTitle());
        assertEquals("A", pinned.getVersionNo());
    }

    @Test
    void submittedAndRejectedContentCannotBeEdited() {
        for (String status : List.of("SUBMITTED", "REJECTED", "APPROVED", "PUBLISHED")) {
            version.setStatus(status);
            SopVersionSaveReqVO req = new SopVersionSaveReqVO().setId(2L).setDocumentId(1L).setVersionNo("A")
                    .setTitle("Attempt mutation").setSteps(List.of(step));
            assertCode(SopErrorConstants.STATE.getCode(), () -> service.saveVersion(req));
        }
        verify(versionMapper, never()).updateById(any(MesSopVersionDO.class));
    }

    @Test
    void mandatoryStepBlocksReportAndDisabledVersionRevokesExistingTask() {
        when(snapshotMapper.findTask(10L)).thenReturn(snapshot);
        assertCode(SopErrorConstants.INCOMPLETE.getCode(), () -> service.validateTaskExecution(10L));
        when(recordMapper.selectList(any(SFunction.class), eq(3L))).thenReturn(List.of(new MesSopStepRecordDO()
                .setStepId("tighten").setConfirmedAt(LocalDateTime.now())));
        assertDoesNotThrow(() -> service.validateTaskExecution(10L));
        version.setStatus("DISABLED");
        assertCode(SopErrorConstants.DISABLED.getCode(), () -> service.validateTaskExecution(10L));
    }

    @Test
    void serverTimingAndEvidenceRequiredBeforeConfirm() {
        when(snapshotMapper.findTask(10L)).thenReturn(snapshot);
        SopExecutionReqVO req = new SopExecutionReqVO().setTaskId(10L).setStepId("tighten");
        assertCode(SopErrorConstants.VIEW_TIME.getCode(), () -> service.confirmStep(req));
        when(recordMapper.findStep(3L, "tighten")).thenReturn(new MesSopStepRecordDO().setId(9L)
                .setViewedAt(LocalDateTime.now().minusSeconds(1)).setViewedBy(77L));
        assertCode(SopErrorConstants.VIEW_TIME.getCode(), () -> service.confirmStep(req));
        verify(recordMapper, never()).updateById(any(MesSopStepRecordDO.class));
    }

    @Test
    void confirmRecordsLoginActorAndRetriesDoNotOverwriteEvidence() {
        when(snapshotMapper.findTask(10L)).thenReturn(snapshot);
        MesSopStepRecordDO record = new MesSopStepRecordDO().setId(9L).setViewedAt(LocalDateTime.now().minusSeconds(10)).setViewedBy(77L);
        when(recordMapper.findStep(3L, "tighten")).thenReturn(record);
        SopExecutionReqVO req = new SopExecutionReqVO().setTaskId(10L).setStepId("tighten");
        service.confirmStep(req);
        assertEquals(77L, record.getConfirmedBy());
        assertEquals("{}", record.getParametersJson());
        req.setParameters(Map.of("forged", BigDecimal.TEN));
        service.confirmStep(req);
        verify(recordMapper, times(1)).updateById(any(MesSopStepRecordDO.class));
        assertEquals("{}", record.getParametersJson());
        InOrder order = inOrder(workOrderMapper, taskMapper, documentMapper, versionMapper);
        order.verify(workOrderMapper).selectByIdForUpdate(20L);
        order.verify(taskMapper).selectByIdForUpdate(10L);
        order.verify(documentMapper).lock(1L);
        order.verify(versionMapper).lock(2L);
    }

    @Test
    void zeroSecondsRequiresViewingButAllowsTimestampRoundedToNextSecond() {
        step.setMinViewSeconds(0);
        snapshot.setStepsJson(JsonUtils.toJsonString(List.of(step)));
        when(snapshotMapper.findTask(10L)).thenReturn(snapshot);
        SopExecutionReqVO req = new SopExecutionReqVO().setTaskId(10L).setStepId("tighten");
        assertCode(SopErrorConstants.VIEW_TIME.getCode(), () -> service.confirmStep(req));
        when(recordMapper.findStep(3L, "tighten")).thenReturn(new MesSopStepRecordDO().setId(9L)
                .setViewedAt(LocalDateTime.now().plusSeconds(1)).setViewedBy(77L));
        assertDoesNotThrow(() -> service.confirmStep(req));
        verify(recordMapper).updateById(any(MesSopStepRecordDO.class));
    }

    @Test
    void viewRetriesKeepFirstServerTimestamp() {
        when(snapshotMapper.findTask(10L)).thenReturn(snapshot);
        SopExecutionReqVO req = new SopExecutionReqVO().setTaskId(10L).setStepId("tighten");
        doAnswer(i -> { when(recordMapper.findStep(3L, "tighten")).thenReturn(i.getArgument(0)); return 1; })
                .when(recordMapper).insert(any(MesSopStepRecordDO.class));
        service.viewStep(req);
        service.viewStep(req);
        verify(recordMapper, times(1)).insert(any(MesSopStepRecordDO.class));
    }

    @Test
    void validatesPhotoAndNumericParameterBounds() {
        step.setPhotoRequired(true).setParameters(List.of(new SopStep.Parameter().setKey("torque").setLabel("扭矩")
                .setMin(new BigDecimal("5")).setMax(new BigDecimal("10"))));
        SopExecutionReqVO req = new SopExecutionReqVO();
        assertThrows(ServiceException.class, () -> MesProSopServiceImpl.validateEvidence(step, req));
        req.setPhotos(List.of("https://factory.test/photo.jpg"));
        assertThrows(ServiceException.class, () -> MesProSopServiceImpl.validateEvidence(step, req));
        req.setParameters(Map.of("torque", new BigDecimal("11")));
        assertThrows(ServiceException.class, () -> MesProSopServiceImpl.validateEvidence(step, req));
        req.setParameters(Map.of("torque", new BigDecimal("5")));
        assertDoesNotThrow(() -> MesProSopServiceImpl.validateEvidence(step, req));
    }

    @Test
    void publicationSerializesAndSupersedesPreviousRelease() {
        MesSopVersionDO next = new MesSopVersionDO().setId(4L).setDocumentId(1L).setStatus("APPROVED");
        when(versionMapper.selectById(4L)).thenReturn(next);
        when(versionMapper.lock(4L)).thenReturn(next);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(version, next));
        service.transition(4L, "publish", null);
        assertEquals("SUPERSEDED", version.getStatus());
        assertEquals("PUBLISHED", next.getStatus());
        assertEquals(77L, next.getPublishedBy());
    }

    private void assertCode(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ServiceException.class, action).getCode());
    }
}
