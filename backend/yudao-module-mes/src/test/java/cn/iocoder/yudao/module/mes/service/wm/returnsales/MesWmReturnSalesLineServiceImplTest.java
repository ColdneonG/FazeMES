package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.MesWmReturnSalesLineMapper;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcCheckResultEnum;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmQualityStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MesWmReturnSalesLineServiceImpl} 的单元测试类
 *
 * @author 芋道源码
 */
@Import({MesWmReturnSalesLineServiceImpl.class,MesWmReturnSalesLineServiceImplTest.Transactions.class})
public class MesWmReturnSalesLineServiceImplTest extends BaseDbUnitTest {
    static class Transactions{@Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource ds){return new JdbcTemplate(ds);}}
    @Resource private JdbcTemplate jdbc;
    @Resource private MesQcRqcMapper rqcMapper;
    @MockitoBean private MesWmReturnSalesSourceService sourceService;
    @org.junit.jupiter.api.AfterEach void clearTenant(){TenantContextHolder.clear();}
    private void completedInspection(MesWmReturnSalesLineDO line,Integer result,BigDecimal good,BigDecimal bad){var qc=new MesQcRqcDO().setCode("RQC-"+line.getId()).setName("真实销售退货检验").setTemplateId(7L).setType(2).setSourceDocType(119).setSourceDocId(line.getReturnId()).setSourceLineId(line.getId()).setItemId(line.getItemId()).setBatchCode(line.getBatchCode()).setCheckQuantity(line.getQuantity()).setQualifiedQuantity(good).setUnqualifiedQuantity(bad).setCheckResult(result).setStatus(4);rqcMapper.insert(qc);returnSalesLineMapper.updateById(new MesWmReturnSalesLineDO().setId(line.getId()).setRqcId(qc.getId()).setOriginTransactionId(23L));}

    @Resource
    private MesWmReturnSalesLineServiceImpl returnSalesLineService;

    @Resource
    private MesWmReturnSalesLineMapper returnSalesLineMapper;

    @MockitoBean
    private MesWmReturnSalesService returnSalesService;

    @MockitoBean
    private MesMdItemService itemService;

    @MockitoBean
    private MesWmBatchService batchService;
    @MockitoBean
    private MesWmReturnSalesDeliveryService deliveryService;

    @MockitoBean
    private MesWmReturnSalesDetailService returnSalesDetailService;

    @org.junit.jupiter.api.BeforeEach
    void sourceIsAwaitingInspection() {
        TenantContextHolder.setTenantId(0L);
        org.mockito.Mockito.when(returnSalesService.validateReturnSalesExists(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> new cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO()
                        .setId(i.getArgument(0)).setStatus(cn.iocoder.yudao.module.mes.enums.wm.MesWmReturnSalesStatusEnum.CONFIRMED.getStatus()));
    }

    @Test
    public void testUpdateReturnSalesLineWhenRqcFinish_allPass() {
        // 准备参数：全部合格
        Integer checkResult = MesQcCheckResultEnum.PASS.getType();
        BigDecimal qualifiedQty = new BigDecimal("100");
        BigDecimal unqualifiedQty = BigDecimal.ZERO;

        // mock 数据
        MesWmReturnSalesLineDO lineDO = randomPojo(MesWmReturnSalesLineDO.class, o -> o.setQuantity(qualifiedQty));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus());
        returnSalesLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用（sourceDocId 传 null 不联动主表状态）
        returnSalesLineService.updateReturnSalesLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：验证质量状态更新为合格
        MesWmReturnSalesLineDO updateLine = returnSalesLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), updateLine.getQualityStatus());
    }

    @Test
    public void testUpdateReturnSalesLineWhenRqcFinish_allFail() {
        // 准备参数：全部不合格
        Integer checkResult = MesQcCheckResultEnum.FAIL.getType();
        BigDecimal qualifiedQty = BigDecimal.ZERO;
        BigDecimal unqualifiedQty = new BigDecimal("100");

        // mock 数据
        MesWmReturnSalesLineDO lineDO = randomPojo(MesWmReturnSalesLineDO.class, o -> o.setQuantity(unqualifiedQty));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus());
        returnSalesLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用
        returnSalesLineService.updateReturnSalesLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：验证质量状态更新为不合格
        MesWmReturnSalesLineDO updateLine = returnSalesLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), updateLine.getQualityStatus());
    }

    @Test
    public void testUpdateReturnSalesLineWhenRqcFinish_partialSplit() {
        // 准备参数：部分合格部分不合格，触发行拆分
        Integer checkResult = MesQcCheckResultEnum.FAIL.getType();
        BigDecimal qualifiedQty = new BigDecimal("70");
        BigDecimal unqualifiedQty = new BigDecimal("30");

        // mock 数据
        MesWmReturnSalesLineDO lineDO = randomPojo(MesWmReturnSalesLineDO.class, o ->
                o.setQuantity(new BigDecimal("100")));
        lineDO.setQualityStatus(MesWmQualityStatusEnum.PENDING.getStatus());
        returnSalesLineMapper.insert(lineDO);
        completedInspection(lineDO,checkResult,qualifiedQty,unqualifiedQty);

        // 调用
        returnSalesLineService.updateReturnSalesLineWhenRqcFinish(
                lineDO.getId(), null, checkResult, qualifiedQty, unqualifiedQty);

        // 断言：原行变为合格，数量调整为合格数量
        MesWmReturnSalesLineDO originalLine = returnSalesLineMapper.selectById(lineDO.getId());
        assertEquals(MesWmQualityStatusEnum.PASS.getStatus(), originalLine.getQualityStatus());
        assertEquals(0, qualifiedQty.compareTo(originalLine.getQuantity()));

        // 断言：新增了一行不合格品
        List<MesWmReturnSalesLineDO> allLines = returnSalesLineMapper.selectListByReturnId(lineDO.getReturnId());
        assertEquals(2, allLines.size());
        MesWmReturnSalesLineDO newLine = allLines.stream()
                .filter(l -> !l.getId().equals(lineDO.getId()))
                .findFirst().orElse(null);
        assertNotNull(newLine);
        assertEquals(MesWmQualityStatusEnum.FAIL.getStatus(), newLine.getQualityStatus());
        assertEquals(0, unqualifiedQty.compareTo(newLine.getQuantity()));
        assertEquals(originalLine.getRqcId(),newLine.getRqcId());assertEquals(23L,newLine.getOriginTransactionId());
    }

}
