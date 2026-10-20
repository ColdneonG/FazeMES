package cn.iocoder.yudao.module.mes.service.wm.returnissue;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnissue.MesWmReturnIssueLineMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MesWmReturnIssueLineServiceImpl} 的单元测试类
 *
 * @author 芋道源码
 */
@Import(MesWmReturnIssueLineServiceImpl.class)
public class MesWmReturnIssueLineServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MesWmReturnIssueLineServiceImpl returnIssueLineService;

    @Resource
    private MesWmReturnIssueLineMapper returnIssueLineMapper;
    @Resource private MesQcRqcMapper rqcMapper;
    private void completedInspection(MesWmReturnIssueLineDO line,Integer result,BigDecimal good,BigDecimal bad){var qc=new MesQcRqcDO().setCode("RQC-"+line.getId()).setName("真实退料检验").setTemplateId(7L).setType(1).setSourceDocType(116).setSourceDocId(line.getIssueId()).setSourceLineId(line.getId()).setItemId(line.getItemId()).setBatchCode(line.getBatchCode()).setCheckQuantity(line.getQuantity()).setQualifiedQuantity(good).setUnqualifiedQuantity(bad).setCheckResult(result).setStatus(4);rqcMapper.insert(qc);returnIssueLineMapper.updateById(new MesWmReturnIssueLineDO().setId(line.getId()).setRqcId(qc.getId()));}

    @MockitoBean
    private MesWmReturnIssueService issueService;

    @MockitoBean
    private MesMdItemService itemService;

    @MockitoBean
    private cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService materialStockService;

    @org.junit.jupiter.api.BeforeEach
    void sourceIsAwaitingInspection() {
        org.mockito.Mockito.when(issueService.validateReturnIssueExists(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> new cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnissue.MesWmReturnIssueDO()
                        .setId(i.getArgument(0)).setStatus(cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnIssueStatusEnum.CONFIRMED.getStatus()));
    }

    @Test
    public void testUpdateReturnIssueLineWhenRqcFinish_allPass() {
        // 准备参数：全部合格
        Integer checkResult = MesQcCheckResultEnum.PASS.getType();
        BigDecimal qualifiedQty = new BigDecimal("100");
        BigDecimal unqualifiedQty = BigDecimal.ZERO;

        // mock 数据
        MesWmReturnIssueLineDO lineDO = randomPojo(MesWmReturnIssueLineDO.class, o -> o.setQuantity(qualifiedQty));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus()).setRqcCheckFlag(true);
        returnIssueLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用（sourceDocId 传 null 不联动主表状态）
        returnIssueLineService.updateReturnIssueLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：验证质量状态更新为合格
        MesWmReturnIssueLineDO updateLine = returnIssueLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), updateLine.getQualityStatus());
    }

    @Test
    public void testUpdateReturnIssueLineWhenRqcFinish_allFail() {
        // 准备参数：全部不合格
        Integer checkResult = MesQcCheckResultEnum.FAIL.getType();
        BigDecimal qualifiedQty = BigDecimal.ZERO;
        BigDecimal unqualifiedQty = new BigDecimal("100");

        // mock 数据
        MesWmReturnIssueLineDO lineDO = randomPojo(MesWmReturnIssueLineDO.class, o -> o.setQuantity(unqualifiedQty));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus()).setRqcCheckFlag(true);
        returnIssueLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用
        returnIssueLineService.updateReturnIssueLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：验证质量状态更新为不合格
        MesWmReturnIssueLineDO updateLine = returnIssueLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), updateLine.getQualityStatus());
    }

    @Test
    public void testUpdateReturnIssueLineWhenRqcFinish_partialSplit() {
        // 准备参数：部分合格部分不合格，触发行拆分
        Integer checkResult = MesQcCheckResultEnum.FAIL.getType();
        BigDecimal qualifiedQty = new BigDecimal("80");
        BigDecimal unqualifiedQty = new BigDecimal("20");

        // mock 数据
        MesWmReturnIssueLineDO lineDO = randomPojo(MesWmReturnIssueLineDO.class, o ->
                o.setQuantity(new BigDecimal("100")));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus()).setRqcCheckFlag(true);
        returnIssueLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用
        returnIssueLineService.updateReturnIssueLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：原行变为合格，数量调整为合格数量
        MesWmReturnIssueLineDO originalLine = returnIssueLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), originalLine.getQualityStatus());
        assertEquals(0, qualifiedQty.compareTo(originalLine.getQuantity()));

        // 断言：新增了一行不合格品
        List<MesWmReturnIssueLineDO> allLines = returnIssueLineMapper.selectListByIssueId(lineDO.getIssueId());
        assertEquals(2, allLines.size());
        MesWmReturnIssueLineDO newLine = allLines.stream()
                .filter(l -> !l.getId().equals(lineDO.getId()))
                .findFirst().orElse(null);
        assertNotNull(newLine);
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), newLine.getQualityStatus());
        assertEquals(0, unqualifiedQty.compareTo(newLine.getQuantity()));
        assertEquals(originalLine.getRqcId(),newLine.getRqcId());
    }
    @Test void completedInspectionMustMatchActualClaimedProductAndSource(){
        for(String corruption:List.of("ITEM","STATUS","OWNER")){
            var source=randomPojo(MesWmReturnIssueLineDO.class,row->row.setQuantity(new BigDecimal("10")).setQualityStatus(0).setRqcCheckFlag(true));returnIssueLineMapper.insert(source);completedInspection(source,1,BigDecimal.TEN,BigDecimal.ZERO);
            var current=returnIssueLineMapper.selectById(source.getId());var qc=rqcMapper.selectById(current.getRqcId());
            if(corruption.equals("ITEM"))rqcMapper.updateById(new MesQcRqcDO().setId(qc.getId()).setItemId(qc.getItemId()+1));else if(corruption.equals("STATUS"))rqcMapper.updateById(new MesQcRqcDO().setId(qc.getId()).setStatus(0));else rqcMapper.updateById(new MesQcRqcDO().setId(qc.getId()).setSourceDocId(qc.getSourceDocId()+1));
            var error=org.junit.jupiter.api.Assertions.assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->returnIssueLineService.updateReturnIssueLineWhenRqcFinish(source.getId(),source.getIssueId(),1,BigDecimal.TEN,BigDecimal.ZERO));
            assertEquals(cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.WM_MATERIAL_STOCK_SELECTION_MISMATCH.getCode(),error.getCode());assertEquals(0,returnIssueLineMapper.selectById(source.getId()).getQualityStatus());
        }
    }

}
