package cn.iocoder.yudao.module.mes.service.insight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** User-facing commands and queries must remain distinct, independently of model output. */
class MesAgentCommandIntentTest {
    @ParameterizedTest(name="{0}")
    @CsvSource(delimiter='|',value={
        "确认工单 WO-001|CONFIRM_WORKORDER",
        "请确认一下这张生产工单 WO-001|CONFIRM_WORKORDER",
        "确认工单|CONFIRM_WORKORDER",
        "把工单 WO-001 确认一下|CONFIRM_WORKORDER",
        "将工单 WO-001 标记为已确认|CONFIRM_WORKORDER",
        "取消工单 WO-001|CANCEL_WORKORDER",
        "请帮我取消这张工单 WO-001|CANCEL_WORKORDER",
        "工单 WO-001 取消掉|CANCEL_WORKORDER",
        "将工单 WO-001 的状态改为已取消|CANCEL_WORKORDER",
        "完成工单 WO-001|FINISH_WORKORDER",
        "完结工单 WO-001|FINISH_WORKORDER",
        "把工单 WO-001 完成|FINISH_WORKORDER",
        "将工单 WO-001 标记为已完成|FINISH_WORKORDER",
        "把工单 WO-001 状态设为完成|FINISH_WORKORDER",
        "结算工单 WO-001 的实际成本|SETTLE_WORKORDER",
        "为工单 WO-001 结算实际成本|SETTLE_WORKORDER",
        "把工单 WO-001 的实际成本结算|SETTLE_WORKORDER",
        "工单 WO-001 进行结算|SETTLE_WORKORDER",
        "把设备 DV-001 的状态改成维护中|UPDATE_DEVICE_STATUS",
        "将设备 DV-001 从维护中改为停机|UPDATE_DEVICE_STATUS",
        "修改设备状态|UPDATE_DEVICE_STATUS",
        "修改设备 DV-001 的状态|UPDATE_DEVICE_STATUS",
        "设备 DV-001 状态修改为维护中|UPDATE_DEVICE_STATUS",
        "把设备状态改成维护中|UPDATE_DEVICE_STATUS",
        "设置设备状态为生产中|UPDATE_DEVICE_STATUS",
        "查看设备状态，并把设备 DV-001 改为维护中|UPDATE_DEVICE_STATUS",
        "查询设备状态并把 DV-001 改为维护中|UPDATE_DEVICE_STATUS",
        "查看工单 WO-001 状态后确认工单 WO-001|CONFIRM_WORKORDER",
        "查询工单 WO-001，然后完成工单 WO-001|FINISH_WORKORDER"
    })
    void supportedCommandsProduceExactlyTheRequestedAction(String question,String kind) {
        assertEquals(Set.of(kind),MesAgentCommandIntent.actionKinds(question));
        assertTrue(MesAgentCommandIntent.isWrite(question));
        assertFalse(MesAgentCommandIntent.hasUnsupportedActions(question));
    }

    @ParameterizedTest(name="[{index}] query: {0}")
    @NullAndEmptySource
    @ValueSource(strings={
        "查询已确认工单", "查询已取消工单", "查询未完成工单", "只看已完成工单",
        "查看工单是否完成", "确认一下工单 WO-001 的状态", "工单 WO-001 完成了吗",
        "查询工单已结算成本", "查看工单 WO-001 的结算结果", "工单成本结算情况",
        "查看设备维护状态", "查询设备 DV-001 状态为维护中", "设备 DV-001 的状态是维护中",
        "工单怎么确认", "为什么不能取消工单", "结算工单需要哪些条件", "如何将设备状态改成维护中",
        "查看修改记录", "查询已提交工单", "查询完成工单 WO-001 后的报表",
        "改为8月份", "换成成品仓", "把月份改成8月", "把查询月份改成8月", "修改查询条件为本月",
        "查2026年9月库存，改为按仓库"
    })
    void queriesExplanationsAndFilterChangesNeverBecomeBusinessCommands(String question) {
        assertEquals(Set.of(),MesAgentCommandIntent.actionKinds(question));
        assertFalse(MesAgentCommandIntent.isWrite(question));
        assertFalse(MesAgentCommandIntent.hasUnsupportedActions(question));
        assertNull(MesAgentCommandIntent.deviceStatus(question));
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={
        "确认并完成工单 WO-001", "确认工单 WO-001，然后取消工单 WO-001",
        "完成工单 WO-001 并结算工单 WO-001 的实际成本",
        "修改工单数量后确认工单 WO-001", "确认工单 WO-001 并修改工单数量",
        "修改设备 DV-001 的名称和状态", "把设备 DV-001 状态改成维护中再改为停机",
        "确认工单 WO-001，同时确认工单 WO-002", "关闭异常 #123 并确认工单 WO-001"
    })
    void multipleOrPartlyUnsupportedOperationsCannotBePartiallyExecuted(String question) {
        assertTrue(MesAgentCommandIntent.isWrite(question));
        assertTrue(MesAgentCommandIntent.actionKinds(question).size()>1
                ||MesAgentCommandIntent.hasUnsupportedActions(question));
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={
        "修改Broky电机采购模块的采购单价为64元", "将工单 WO-001 排到明天",
        "为工单 WO-001 预占物料", "释放工单 WO-001 的预占", "把设备 DV-001 的数量改成100",
        "给工单 WO-001 记入2小时人工成本", "执行采购入库单 RK-001"
    })
    void unsupportedWritesRemainWritesRatherThanFallingBackToQueries(String question) {
        assertTrue(MesAgentCommandIntent.isWrite(question));
        assertEquals(Set.of(),MesAgentCommandIntent.actionKinds(question));
        assertTrue(MesAgentCommandIntent.hasUnsupportedActions(question));
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={
        "新建异常：工单 WO-001 缺料，建议取消工单", "请帮我新建一个异常：设备状态需要修改",
        "将异常 #123 标记为处理中", "将异常 #123 从已关闭转为处理中", "关闭异常 #123"
    })
    void incidentOperationsStillReachTheirExistingToolWithoutBorrowingQueryContext(String question) {
        assertTrue(MesAgentCommandIntent.isWrite(question));
        assertEquals(Set.of(),MesAgentCommandIntent.actionKinds(question));
        assertFalse(MesAgentCommandIntent.hasUnsupportedActions(question));
    }

    @ParameterizedTest(name="{0}")
    @CsvSource(delimiter='|',value={
        "把设备 DV-001 的状态改成维护中|3",
        "将设备 DV-001 从维护中改为停机|1",
        "将设备 DV-001 从停机改为生产中|2",
        "设备 DV-001 状态修改为维护中|3",
        "设置设备状态为生产中|2",
        "查看设备状态，并把设备 DV-001 改为维护中|3"
    })
    void deviceStatusComesFromTheExplicitTargetRatherThanTheSourceState(String question,int target) {
        assertEquals(target,MesAgentCommandIntent.deviceStatus(question));
    }

    @Test void aDeviceCommandWithoutATargetStillNeedsUserSelection() {
        assertEquals(Set.of("UPDATE_DEVICE_STATUS"),MesAgentCommandIntent.actionKinds("修改设备状态"));
        assertNull(MesAgentCommandIntent.deviceStatus("修改设备状态"));
        assertNull(MesAgentCommandIntent.deviceStatus("把设备状态改成维修中"));
    }
}
