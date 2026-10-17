package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.worker.MesMdWorkstationWorkerSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.tool.MesMdWorkstationToolSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.tm.tool.MesTmToolDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.*;
import cn.iocoder.yudao.module.mes.dal.mysql.tm.tool.MesTmToolMapper;
import cn.iocoder.yudao.module.mes.service.tm.tool.MesTmToolTypeService;
import cn.iocoder.yudao.module.system.api.dept.PostApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMdWorkstationResourceBindingTest extends BaseMockitoUnitTest {
    @InjectMocks private MesMdWorkstationWorkerServiceImpl workerService;
    @InjectMocks private MesMdWorkstationToolServiceImpl toolService;
    @Mock private MesMdWorkstationWorkerMapper workerMapper;
    @Mock private MesMdWorkstationToolMapper toolMapper;
    @Mock private MesTmToolMapper toolLedgerMapper;
    @Mock private PostApi postApi;
    @Mock private AdminUserApi adminUserApi;
    @Mock private MesTmToolTypeService toolTypeService;
    private MesMdWorkstationWorkerSaveReqVO worker() {
        return new MesMdWorkstationWorkerSaveReqVO().setWorkstationId(1L).setPostId(2L).setQuantity(1).setUserIds(List.of(5L));
    }
    private MesMdWorkstationToolSaveReqVO tool() {
        return new MesMdWorkstationToolSaveReqVO().setWorkstationId(1L).setToolTypeId(3L).setQuantity(2).setToolIds(List.of(6L));
    }
    @Test void bindingMustBelongToRoleAndCannotCountUserTwice() {
        when(adminUserApi.validateUser(5L)).thenReturn(new AdminUserRespDTO().setPostIds(Set.of(8L)));
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker()));
        when(adminUserApi.validateUser(5L)).thenReturn(new AdminUserRespDTO().setPostIds(Set.of(2L)));
        when(workerMapper.selectListByWorkstationId(1L)).thenReturn(List.of(new MesMdWorkstationWorkerDO().setId(7L).setUserIds(List.of(5L))));
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker()));
        verify(workerMapper, never()).insert(any(MesMdWorkstationWorkerDO.class));
    }
    @Test void disabledUserRejectedBySystemValidationAndNoBindingIsValid() {
        doThrow(new ServiceException(400, "disabled")).when(adminUserApi).validateUser(5L);
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker()));
        workerService.createWorkstationWorker(worker().setUserIds(List.of()));
        verify(workerMapper).insert(argThat((MesMdWorkstationWorkerDO row) -> row.getUserIds().isEmpty()));
    }
    @Test void positiveDemandAndUniqueBindingsAreRequired() {
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker().setQuantity(0)));
        assertThrows(ServiceException.class, () -> toolService.createWorkstationTool(tool().setQuantity(0)));
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker().setUserIds(List.of(5L, 5L))));
        assertThrows(ServiceException.class, () -> toolService.createWorkstationTool(tool().setToolIds(List.of(6L, 6L))));
    }
    @Test void explicitNullBindingIsRejectedAsBusinessError() {
        assertThrows(ServiceException.class, () -> workerService.createWorkstationWorker(worker().setUserIds(Arrays.asList(5L, null))));
        assertThrows(ServiceException.class, () -> toolService.createWorkstationTool(tool().setToolIds(Arrays.asList(6L, null))));
    }
    @Test void toolBindingMustMatchActualLedgerType() {
        assertThrows(ServiceException.class, () -> toolService.createWorkstationTool(tool()));
        when(toolLedgerMapper.selectById(6L)).thenReturn(new MesTmToolDO().setToolTypeId(4L));
        assertThrows(ServiceException.class, () -> toolService.createWorkstationTool(tool()));
        when(toolLedgerMapper.selectById(6L)).thenReturn(new MesTmToolDO().setToolTypeId(3L));
        toolService.createWorkstationTool(tool());
        verify(toolMapper).insert(argThat((MesMdWorkstationToolDO row) -> row.getToolIds().equals(List.of(6L))));
    }
}
