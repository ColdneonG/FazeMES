package cn.iocoder.yudao.module.mes.service.qc.rqc;

import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.vo.MesQcRqcSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.rqc.MesQcRqcDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.qc.template.MesQcTemplateItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.MesWmReturnSalesDO;
import cn.iocoder.yudao.module.mes.dal.mysql.qc.rqc.MesQcRqcMapper;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.qc.defectrecord.MesQcDefectRecordService;
import cn.iocoder.yudao.module.mes.service.qc.indicatorresult.MesQcIndicatorResultService;
import cn.iocoder.yudao.module.mes.service.qc.template.MesQcTemplateItemService;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueLineService;
import cn.iocoder.yudao.module.mes.service.wm.returnissue.MesWmReturnIssueService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesLineService;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.mes.service.wm.returnsales.MesWmReturnSalesSourceService;
import cn.iocoder.yudao.module.mes.controller.admin.qc.rqc.MesQcRqcController;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesQcRqcSourceIntegrityTest {
    MesQcRqcServiceImpl service;MesQcRqcMapper mapper;MesQcTemplateItemService templates;MesWmReturnSalesLineService lines;
    JdbcTemplate jdbc;MesWmReturnSalesSourceService sources;Map<Long,MesQcRqcDO> records;
    @BeforeEach void setup(){
        service=new MesQcRqcServiceImpl();mapper=mock(MesQcRqcMapper.class);templates=mock(MesQcTemplateItemService.class);lines=mock(MesWmReturnSalesLineService.class);
        ReflectionTestUtils.setField(service,"rqcMapper",mapper);ReflectionTestUtils.setField(service,"templateItemService",templates);ReflectionTestUtils.setField(service,"rqcLineService",mock(MesQcRqcLineService.class));
        ReflectionTestUtils.setField(service,"itemService",mock(MesMdItemService.class));ReflectionTestUtils.setField(service,"adminUserApi",mock(AdminUserApi.class));ReflectionTestUtils.setField(service,"indicatorResultService",mock(MesQcIndicatorResultService.class));
        ReflectionTestUtils.setField(service,"returnSalesLineService",lines);ReflectionTestUtils.setField(service,"returnIssueLineService",mock(MesWmReturnIssueLineService.class));
        var sales=mock(MesWmReturnSalesService.class);when(sales.getReturnSales(10L)).thenReturn(new MesWmReturnSalesDO().setId(10L).setCode("RETURN-A"));ReflectionTestUtils.setField(service,"returnSalesService",sales);ReflectionTestUtils.setField(service,"returnIssueService",mock(MesWmReturnIssueService.class));ReflectionTestUtils.setField(service,"defectRecordService",mock(MesQcDefectRecordService.class));
        when(templates.getRequiredTemplateByItemIdAndType(anyLong(),anyInt())).thenReturn(new MesQcTemplateItemDO().setTemplateId(7L));
        doAnswer(call->{MesQcRqcDO row=call.getArgument(0);row.setId(1L);return 1;}).when(mapper).insert(any(MesQcRqcDO.class));
        initializeSources();
    }
    void initializeSources(){
        TenantContextHolder.setTenantId(1L);jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:rqc_integrity_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE mes_wm_return_sales(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),status INT,client_id BIGINT,deleted INT DEFAULT 0)");jdbc.execute("CREATE TABLE mes_wm_return_issue(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),status INT,deleted INT DEFAULT 0)");
        for(String table:List.of("mes_wm_return_sales_line","mes_wm_return_issue_line"))jdbc.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,tenant_id BIGINT,"+(table.contains("sales")?"return_id":"issue_id")+" BIGINT,item_id BIGINT,batch_id BIGINT,batch_code VARCHAR(64),quantity DECIMAL(20,6),rqc_check_flag BOOLEAN,rqc_id BIGINT,quality_status INT,origin_transaction_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO mes_wm_return_sales VALUES(10,1,'RETURN-A',1,7,0),(12,2,'PRIVATE',1,7,0)");jdbc.update("INSERT INTO mes_wm_return_issue VALUES(11,1,'ISSUE-A',1,0)");jdbc.update("INSERT INTO mes_wm_return_sales_line VALUES(20,1,10,100,1,'B1',10,TRUE,NULL,0,500,0),(22,2,12,100,1,'B1',10,TRUE,NULL,0,500,0)");jdbc.update("INSERT INTO mes_wm_return_issue_line VALUES(21,1,11,100,1,'B1',10,TRUE,NULL,0,NULL,0)");
        sources=mock(MesWmReturnSalesSourceService.class);when(sources.lockForRqc(anyLong(),anyLong())).thenReturn(500L);doAnswer(call->{long parent=call.getArgument(0);if(!Objects.equals(jdbc.queryForObject("SELECT client_id FROM mes_wm_return_sales WHERE id=?",Long.class,parent),7L))throw new ServiceException(1,"原销售客户不一致");return null;}).when(sources).validateForRqc(anyLong(),anyLong(),anyLong());
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"returnSalesSourceService",sources);
        records=new LinkedHashMap<>();doAnswer(call->{MesQcRqcDO row=call.getArgument(0);row.setId(1L);records.put(1L,row);return 1;}).when(mapper).insert(any(MesQcRqcDO.class));when(mapper.selectById(anyLong())).thenAnswer(call->records.get(call.getArgument(0)));when(mapper.selectOne(any(Wrapper.class))).thenAnswer(call->records.get(1L));doAnswer(call->{MesQcRqcDO change=call.getArgument(0),current=records.get(change.getId());if(change.getStatus()!=null)current.setStatus(change.getStatus());return 1;}).when(mapper).updateById(any(MesQcRqcDO.class));doAnswer(call->{records.remove(call.getArgument(0));return 1;}).when(mapper).deleteById(anyLong());
    }
    @AfterEach void cleanup(){TenantContextHolder.clear();jdbc.execute("DROP ALL OBJECTS");}
    private MesQcRqcSaveReqVO request(){return new MesQcRqcSaveReqVO().setCode("QC-A").setName("验货").setType(2).setSourceDocType(119).setSourceDocId(10L).setSourceLineId(20L).setItemId(999L).setBatchCode("WRONG").setCheckQuantity(BigDecimal.TEN).setQualifiedQuantity(BigDecimal.TEN).setUnqualifiedQuantity(BigDecimal.ZERO).setCheckResult(1);}
    @Test void createCannotBindArbitraryProductAndBatchToAnotherReturnLine(){assertThrows(ServiceException.class,()->service.createRqc(request()));verify(mapper,never()).insert(any(MesQcRqcDO.class));}
    @Test void forgedPersistedPassCannotApproveAnotherReturnedProduct(){
        long id=service.createRqc(valid());records.get(id).setItemId(999L).setBatchCode("WRONG");
        assertThrows(ServiceException.class,()->service.finishRqc(id));verifyNoInteractions(lines);assertEquals(0,records.get(id).getStatus());
    }
    @Test void templateAlwaysUsesRqcFamilyRatherThanReturnSubtype(){
        var manual=request().setSourceDocType(null).setSourceDocId(null).setSourceLineId(null);
        service.createRqc(manual);verify(templates).getRequiredTemplateByItemIdAndType(999L,4);
    }
    MesQcRqcSaveReqVO valid(){return request().setItemId(100L).setBatchCode("B1");}
    @Test void sourceContextIsCanonicalMinimalExactAndQcCreateOnly()throws Exception{var context=service.getSourceContext(119,10L,20L);assertEquals(7L,context.getClientId());assertEquals(500L,context.getOriginTransactionId());assertEquals(100L,context.getItemId());assertEquals("RETURN-A",context.getSourceDocCode());var json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(context));assertEquals(10,json.size());assertTrue(json.get("checkQuantity").isTextual());assertEquals("10.000000",json.get("checkQuantity").asText());assertEquals("@ss.hasPermission('mes:qc-rqc:create')",MesQcRqcController.class.getMethod("sourceContext",Integer.class,Long.class,Long.class).getAnnotation(PreAuthorize.class).value());}
    @Test void productionContextHasNoCustomerOrOriginAndClaimsPendingLine(){var context=service.getSourceContext(116,11L,21L);assertNull(context.getClientId());assertNull(context.getOriginTransactionId());service.createRqc(valid().setType(1).setSourceDocType(116).setSourceDocId(11L).setSourceLineId(21L));assertEquals(1L,jdbc.queryForObject("SELECT rqc_id FROM mes_wm_return_issue_line WHERE id=21",Long.class));verifyNoInteractions(sources);}
    @Test void duplicateInspectionAndClaimMismatchCannotReleaseStock(){long id=service.createRqc(valid());assertThrows(ServiceException.class,()->service.createRqc(valid().setCode("SECOND")));jdbc.update("UPDATE mes_wm_return_sales_line SET rqc_id=99 WHERE id=20");assertThrows(ServiceException.class,()->service.finishRqc(id));verifyNoInteractions(lines);}
    @Test void customerProvenanceRecheckedAtFinish(){long id=service.createRqc(valid());jdbc.update("UPDATE mes_wm_return_sales SET client_id=999 WHERE id=10");assertThrows(ServiceException.class,()->service.finishRqc(id));assertEquals(0,records.get(id).getStatus());verifyNoInteractions(lines);}
    @Test void quantityBatchAndCanceledParentAreRechecked(){long id=service.createRqc(valid());for(String change:List.of("UPDATE mes_wm_return_sales_line SET quantity=9 WHERE id=20","UPDATE mes_wm_return_sales_line SET batch_code='CHANGED' WHERE id=20","UPDATE mes_wm_return_sales SET status=5 WHERE id=10")){jdbc.update(change);assertThrows(ServiceException.class,()->service.finishRqc(id));jdbc.update("UPDATE mes_wm_return_sales_line SET quantity=10,batch_code='B1' WHERE id=20");jdbc.update("UPDATE mes_wm_return_sales SET status=1 WHERE id=10");}verifyNoInteractions(lines);}
    @Test void quantitiesMustBeNonnegativeConservedAndMatchResult(){for(var r:List.of(valid().setQualifiedQuantity(new BigDecimal("11")).setUnqualifiedQuantity(new BigDecimal("-1")),valid().setCheckResult(3),valid().setQualifiedQuantity(new BigDecimal("9")).setUnqualifiedQuantity(BigDecimal.ONE)))assertThrows(ServiceException.class,()->service.createRqc(r));}
    @Test void finishUsesClaimedPersistedIdentityAndWritesCompletedStateBeforeCallback(){long id=service.createRqc(valid());service.finishRqc(id);assertEquals(4,records.get(id).getStatus());verify(lines).updateReturnSalesLineWhenRqcFinish(eq(20L),eq(10L),eq(1),argThat(q->q.compareTo(BigDecimal.TEN)==0),argThat(q->q.signum()==0));assertThrows(ServiceException.class,()->service.finishRqc(id));}
    @Test void sourceIdentityCannotChangeOnUpdate(){long id=service.createRqc(valid());assertThrows(ServiceException.class,()->service.updateRqc(valid().setId(id).setSourceLineId(21L)));assertThrows(ServiceException.class,()->service.updateRqc(valid().setId(id).setItemId(999L)));assertThrows(ServiceException.class,()->service.updateRqc(valid().setId(id).setCheckQuantity(BigDecimal.ONE)));}
    @Test void unrequiredAndWrongTenantContextsAreRejected(){jdbc.update("UPDATE mes_wm_return_sales_line SET rqc_check_flag=FALSE WHERE id=20");assertThrows(ServiceException.class,()->service.getSourceContext(119,10L,20L));assertThrows(ServiceException.class,()->service.getSourceContext(119,12L,22L));}
    @Test void partialAndUnknownManualSourcesAreRejected(){for(var r:List.of(valid().setSourceDocType(0).setSourceDocId(null),valid().setSourceDocType(null).setSourceLineId(null),valid().setSourceDocType(777)))assertThrows(ServiceException.class,()->service.createRqc(r));}
    @Test void deleteDraftReleasesOnlyItsOwnClaim(){long id=service.createRqc(valid());service.deleteRqc(id);assertFalse(records.containsKey(id));assertNull(jdbc.queryForObject("SELECT rqc_id FROM mes_wm_return_sales_line WHERE id=20",Long.class));verifyNoInteractions(lines);}
    @Test void invalidOldDraftIsDeletableWithoutForeignCallback(){long id=service.createRqc(valid());records.get(id).setSourceDocId(12L).setSourceLineId(22L);service.deleteRqc(id);assertFalse(records.containsKey(id));assertNull(jdbc.queryForObject("SELECT rqc_id FROM mes_wm_return_sales_line WHERE id=22",Long.class));assertEquals(id,jdbc.queryForObject("SELECT rqc_id FROM mes_wm_return_sales_line WHERE id=20",Long.class));verifyNoInteractions(lines);}
}
