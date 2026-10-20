package cn.iocoder.yudao.module.mes.service.qc.rqc;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.qc.MesQcStatusEnum;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.defectrecord.MesQcDefectRecordService;
import cn.iocoder.yudao.module.mes.service.qc.template.MesQcTemplateItemService;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueLineService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesLineService;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcRespVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.MesQcTemplateItemDO;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.QC_RQC_NOT_EXISTS;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.QC_RQC_NOT_PREPARE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MesQcRqcServiceImpl} 的单元测试类
 *
 * @author 芋道源码
 */
@Import({MesQcRqcServiceImpl.class,MesQcRqcServiceImplTest.Transactions.class})
public class MesQcRqcServiceImplTest extends BaseDbUnitTest {
    static class Transactions{@Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource ds){return new JdbcTemplate(ds);}}
    @Resource private JdbcTemplate jdbc;
    @MockitoBean private MesWmReturnSalesSourceService returnSalesSourceService;
    @BeforeEach void canonicalSources(){TenantContextHolder.setTenantId(0L);org.mockito.Mockito.when(returnSalesSourceService.lockForRqc(anyLong(),anyLong())).thenReturn(500L);jdbc.execute("CREATE TABLE IF NOT EXISTS mes_wm_return_issue(id BIGINT PRIMARY KEY,code VARCHAR(255),status INT,tenant_id BIGINT,deleted BOOLEAN DEFAULT FALSE)");}
    @AfterEach void clearSources(){jdbc.update("DELETE FROM mes_wm_return_issue WHERE code='RQC-FIXTURE'");jdbc.update("DELETE FROM mes_wm_return_sales WHERE code='RQC-FIXTURE'");TenantContextHolder.clear();}
    private void source(MesQcRqcDO qc,boolean sales){String parent=sales?"mes_wm_return_sales":"mes_wm_return_issue",line=sales?"mes_wm_return_sales_line":"mes_wm_return_issue_line",owner=sales?"return_id":"issue_id";if(sales)jdbc.update("INSERT INTO "+parent+"(id,code,status,client_id,tenant_id,deleted) VALUES(?,'RQC-FIXTURE',1,1,0,FALSE)",qc.getSourceDocId());else jdbc.update("INSERT INTO "+parent+"(id,code,status,tenant_id,deleted) VALUES(?,'RQC-FIXTURE',1,0,FALSE)",qc.getSourceDocId());String origin=sales?",origin_transaction_id":"",value=sales?",500":"";jdbc.update("INSERT INTO "+line+"(id,"+owner+",item_id,batch_code,quantity,rqc_check_flag,rqc_id,quality_status,tenant_id,deleted"+origin+") VALUES(?,?,?,?,?,TRUE,?,0,0,FALSE"+value+")",qc.getSourceLineId(),qc.getSourceDocId(),qc.getItemId(),qc.getBatchCode(),qc.getCheckQuantity(),qc.getId());}

    @Resource
    private MesQcRqcServiceImpl rqcService;

    @Resource
    private MesQcRqcMapper rqcMapper;

    @MockitoBean
    private MesWmReturnIssueLineService returnIssueLineService;
    @MockitoBean
    private MesWmReturnSalesLineService returnSalesLineService;
    @MockitoBean
    private MesQcRqcLineService rqcLineService;
    @MockitoBean
    private MesMdItemService itemService;
    @MockitoBean
    private MesQcTemplateItemService templateItemService;
    @MockitoBean
    private MesQcDefectRecordService defectRecordService;
    @MockitoBean
    private AdminUserApi adminUserApi;
    @MockitoBean
    private MesQcIndicatorResultService indicatorResultService;

    @Test
    public void testFinishRqc_successWithReturnIssue() {
        // 准备参数
        Long sourceLineId = randomLongId();
        Long sourceDocId = randomLongId();
        Integer checkResult = 2;
        BigDecimal qualifiedQty = new BigDecimal("80");
        BigDecimal unqualifiedQty = new BigDecimal("20");

        // mock 数据
        MesQcRqcDO rqc = randomPojo(MesQcRqcDO.class, o -> {
            o.setStatus(MesQcStatusEnum.DRAFT.getStatus());
            o.setSourceDocType(MesBizTypeConstants.WM_RETURN_ISSUE);
            o.setSourceDocId(sourceDocId);
            o.setSourceLineId(sourceLineId);
            o.setCheckResult(checkResult);
            // 设置数量通过 finishRqc 的数量校验：合格 + 不合格 = 检测数量
            o.setCheckQuantity(new BigDecimal("100"));
            o.setQualifiedQuantity(qualifiedQty);
            o.setUnqualifiedQuantity(unqualifiedQty);
        });
        rqcMapper.insert(rqc);
        source(rqc,false);

        // 调用
        rqcService.finishRqc(rqc.getId());

        // 断言：验证状态更新
        MesQcRqcDO updatedRqc = rqcMapper.selectById(rqc.getId());
        assertEquals(MesQcStatusEnum.FINISHED.getStatus(), updatedRqc.getStatus());

        // 断言：验证回写调用生产退料（含拆分参数）
        verify(returnIssueLineService).updateReturnIssueLineWhenRqcFinish(
                eq(sourceLineId), eq(sourceDocId), eq(checkResult),
                argThat(v -> v.compareTo(qualifiedQty) == 0),
                argThat(v -> v.compareTo(unqualifiedQty) == 0));
        // 断言：未调用销售退货
        verify(returnSalesLineService, never()).updateReturnSalesLineWhenRqcFinish(
                anyLong(), anyLong(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    public void testFinishRqc_successWithReturnSales() {
        // 准备参数
        Long sourceLineId = randomLongId();
        Long sourceDocId = randomLongId();
        Integer checkResult = 2;
        BigDecimal qualifiedQty = new BigDecimal("90");
        BigDecimal unqualifiedQty = new BigDecimal("10");

        // mock 数据
        MesQcRqcDO rqc = randomPojo(MesQcRqcDO.class, o -> {
            o.setStatus(MesQcStatusEnum.DRAFT.getStatus());
            o.setSourceDocType(MesBizTypeConstants.WM_RETURN_SALES);
            o.setSourceDocId(sourceDocId);
            o.setSourceLineId(sourceLineId);
            o.setCheckResult(checkResult);
            // 设置数量通过 finishRqc 的数量校验：合格 + 不合格 = 检测数量
            o.setCheckQuantity(new BigDecimal("100"));
            o.setQualifiedQuantity(qualifiedQty);
            o.setUnqualifiedQuantity(unqualifiedQty);
        });
        rqcMapper.insert(rqc);
        source(rqc,true);

        // 调用
        rqcService.finishRqc(rqc.getId());

        // 断言：验证状态更新
        MesQcRqcDO updatedRqc = rqcMapper.selectById(rqc.getId());
        assertEquals(MesQcStatusEnum.FINISHED.getStatus(), updatedRqc.getStatus());

        // 断言：验证回写调用销售退货（含拆分参数）
        verify(returnSalesLineService).updateReturnSalesLineWhenRqcFinish(
                eq(sourceLineId), eq(sourceDocId), eq(checkResult),
                argThat(v -> v.compareTo(qualifiedQty) == 0),
                argThat(v -> v.compareTo(unqualifiedQty) == 0));
        // 断言：未调用生产退料
        verify(returnIssueLineService, never()).updateReturnIssueLineWhenRqcFinish(
                anyLong(), anyLong(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    public void testFinishRqc_notExists() {
        // 准备参数
        Long rqcId = randomLongId();

        // 调用，并断言异常
        assertServiceException(() -> rqcService.finishRqc(rqcId), QC_RQC_NOT_EXISTS);
    }

    @Test
    public void testFinishRqc_statusNotDraft() {
        // 准备参数
        // mock 数据：状态为已完成
        MesQcRqcDO rqc = randomPojo(MesQcRqcDO.class, o -> {
            o.setStatus(MesQcStatusEnum.FINISHED.getStatus());
            o.setSourceDocType(0);o.setSourceDocId(null);o.setSourceLineId(null);
        });
        rqcMapper.insert(rqc);

        // 调用，并断言异常
        assertServiceException(() -> rqcService.finishRqc(rqc.getId()), QC_RQC_NOT_PREPARE);
    }

    @Test void millionthQuantityPersistsClaimsFinishesAndSerializesExactly()throws Exception{
        long parent=randomLongId(),line=randomLongId();BigDecimal micro=new BigDecimal("0.000001");
        jdbc.update("INSERT INTO mes_wm_return_sales(id,code,status,client_id,tenant_id,deleted) VALUES(?,'RQC-FIXTURE',1,1,0,FALSE)",parent);
        jdbc.update("INSERT INTO mes_wm_return_sales_line(id,return_id,item_id,batch_code,quantity,rqc_check_flag,quality_status,origin_transaction_id,tenant_id,deleted) VALUES(?,?,7,'MICRO',?,TRUE,0,500,0,FALSE)",line,parent,micro);
        org.mockito.Mockito.when(templateItemService.getRequiredTemplateByItemIdAndType(7L,4)).thenReturn(new MesQcTemplateItemDO().setTemplateId(7L));
        var req=new MesQcRqcSaveReqVO().setCode("RQC-MICRO").setName("百万分之一").setType(2).setSourceDocType(119).setSourceDocId(parent).setSourceLineId(line).setItemId(7L).setBatchCode("MICRO").setCheckQuantity(micro).setQualifiedQuantity(micro).setUnqualifiedQuantity(BigDecimal.ZERO).setCheckResult(1).setInspectDate(LocalDateTime.now()).setInspectorUserId(1L);
        long id=rqcService.createRqc(req);assertEquals(0,micro.compareTo(rqcMapper.selectById(id).getCheckQuantity()));assertEquals(id,jdbc.queryForObject("SELECT rqc_id FROM mes_wm_return_sales_line WHERE id=?",Long.class,line));
        rqcService.finishRqc(id);assertEquals(4,rqcMapper.selectById(id).getStatus());
        var tree=new ObjectMapper().findAndRegisterModules().valueToTree(BeanUtils.toBean(rqcMapper.selectById(id),MesQcRqcRespVO.class));assertEquals("0.000001",tree.get("checkQuantity").asText());org.junit.jupiter.api.Assertions.assertTrue(tree.get("checkQuantity").isTextual());org.junit.jupiter.api.Assertions.assertTrue(tree.get("qualifiedQuantity").isTextual());org.junit.jupiter.api.Assertions.assertTrue(tree.get("unqualifiedQuantity").isTextual());
    }
    @Test void manualDraftMayEditItsOwnQuantityWithoutAnySourceCallback(){
        org.mockito.Mockito.when(templateItemService.getRequiredTemplateByItemIdAndType(7L,4)).thenReturn(new MesQcTemplateItemDO().setTemplateId(7L));
        var req=new MesQcRqcSaveReqVO().setCode("RQC-MANUAL").setName("手工检验").setType(2).setSourceDocType(0).setItemId(7L).setCheckQuantity(new BigDecimal("2")).setQualifiedQuantity(new BigDecimal("2")).setUnqualifiedQuantity(BigDecimal.ZERO).setCheckResult(1).setInspectDate(LocalDateTime.now()).setInspectorUserId(1L);
        long id=rqcService.createRqc(req);rqcService.updateRqc(req.setId(id).setCheckQuantity(new BigDecimal("3")).setQualifiedQuantity(new BigDecimal("3")).setBatchCode("MANUAL-B"));assertEquals(0,new BigDecimal("3").compareTo(rqcMapper.selectById(id).getCheckQuantity()));rqcService.finishRqc(id);assertEquals(4,rqcMapper.selectById(id).getStatus());org.mockito.Mockito.verifyNoInteractions(returnIssueLineService,returnSalesLineService);
    }

}
