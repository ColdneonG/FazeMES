package cn.iocoder.yudao.module.mes.service.wm.salesnotice;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productsales.MesWmProductSalesLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productsales.MesWmProductSalesMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/** Notice is the mutex for every reservation/fulfillment mutation. Every evidence read is current. */
@Service
public class MesWmSalesNoticeFulfillmentService {
    @Resource private JdbcTemplate jdbcTemplate;
    @Resource private MesWmProductSalesMapper productSalesMapper;

    @Data public static class Summary {
        private BigDecimal noticeQuantity, shippedQuantity=BigDecimal.ZERO, reservedQuantity=BigDecimal.ZERO,
                availableQuantity=BigDecimal.ZERO, editableAvailableQuantity=BigDecimal.ZERO;
        private boolean fulfillmentReliable=true;
        private String fulfillmentWarning;
    }
    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private ServiceException invalid(String message){return exception(WM_SALES_NOTICE_FULFILLMENT_INVALID,message);}
    private Long id(Object value){return value==null?null:((Number)value).longValue();}
    private BigDecimal decimal(Object value){return value==null?null:new BigDecimal(value.toString());}
    private boolean deleted(Map<String,Object> row){Object v=row.get("deleted");return v instanceof Boolean?(Boolean)v:v!=null&&((Number)v).intValue()!=0;}
    private boolean bool(Object v){return Boolean.TRUE.equals(v)||(v instanceof Number&&((Number)v).intValue()!=0);}
    private int status(Map<String,Object> row){return row.get("status")==null?-1:((Number)row.get("status")).intValue();}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbcTemplate.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private List<Map<String,Object>> current(String sql,Object...args){return jdbcTemplate.queryForList(sql+" ORDER BY id FOR UPDATE",args);}
    public void requireQuantity(BigDecimal quantity){if(quantity==null||quantity.signum()<=0||quantity.scale()>6||quantity.precision()-quantity.scale()>14)throw invalid("数量必须为大于零且不超过六位小数的有效数值");}

    public Map<String,Object> lockNotice(Long noticeId) {
        var notice=one("SELECT * FROM mes_wm_sales_notice WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",noticeId,tenant());
        if(notice==null)throw exception(WM_SALES_NOTICE_NOT_EXISTS);return notice;
    }
    public void requireOpenNotice(Long noticeId,Long clientId){
        var n=lockNotice(noticeId);if(status(n)!=3)throw exception(WM_SALES_NOTICE_STATUS_NOT_APPROVED);
        if(!Objects.equals(id(n.get("client_id")),clientId))throw exception(WM_SALES_NOTICE_CLIENT_MISMATCH);
    }
    public void requireDraftNoticeWithoutSales(Long noticeId){
        var notice=lockNotice(noticeId);if(status(notice)!=0)throw exception(WM_SALES_NOTICE_STATUS_NOT_ALLOW_UPDATE);
        if(one("SELECT id FROM mes_wm_product_sales WHERE tenant_id=? AND notice_id=? LIMIT 1 FOR UPDATE",tenant(),noticeId)!=null)
            throw invalid("通知存在销售出库历史，不能按草稿修改或删除");
    }
    /** Locate without locking, then notice -> sales; reject a stale parent rather than reverse the lock order. */
    public MesWmProductSalesDO lockSales(Long salesId) {
        var located=productSalesMapper.selectById(salesId);if(located==null)throw exception(WM_PRODUCT_SALES_NOT_EXISTS);
        if(located.getNoticeId()!=null)lockNotice(located.getNoticeId());
        var sales=productSalesMapper.selectForUpdate(salesId);
        if(sales==null)throw exception(WM_PRODUCT_SALES_NOT_EXISTS);
        if(!Objects.equals(located.getNoticeId(),sales.getNoticeId()))throw invalid("通知关联已改变，请刷新后重试");
        return sales;
    }
    /** Used before header reassignment, so both old/new notice mutexes precede the sales row. */
    public MesWmProductSalesDO lockSalesForUpdate(Long salesId,Long targetNoticeId){
        var located=productSalesMapper.selectById(salesId);if(located==null)throw exception(WM_PRODUCT_SALES_NOT_EXISTS);
        var ids=new TreeSet<Long>();if(located.getNoticeId()!=null)ids.add(located.getNoticeId());if(targetNoticeId!=null)ids.add(targetNoticeId);
        ids.forEach(this::lockNotice);var sales=productSalesMapper.selectForUpdate(salesId);
        if(sales==null||!Objects.equals(located.getNoticeId(),sales.getNoticeId()))throw invalid("通知关联已改变，请刷新后重试");return sales;
    }
    private String marks(Collection<?> ids){return String.join(",",Collections.nCopies(ids.size(),"?"));}
    private List<Map<String,Object>> evidenceRows(String table,Collection<Long> ids,String column){
        if(ids.isEmpty())return List.of();var args=new ArrayList<Object>();args.add(tenant());args.addAll(ids);
        return current("SELECT * FROM "+table+" WHERE tenant_id=?"+(table.equals("mes_wm_transaction")?" AND biz_type=118":"")+" AND "+column+" IN ("+marks(ids)+")",args.toArray());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<Long,Summary> summaries(Long noticeId,Long editingSalesLineId){
        var notice=lockNotice(noticeId);
        // Include soft-deleted evidence: historical shipments must never silently release the notice quota.
        var noticeLines=current("SELECT * FROM mes_wm_sales_notice_line WHERE tenant_id=? AND notice_id=?",tenant(),noticeId);
        var noticeIds=new LinkedHashSet<Long>();var result=new LinkedHashMap<Long,Summary>();
        for(var n:noticeLines){noticeIds.add(id(n.get("id")));if(!deleted(n)){var s=new Summary();s.setNoticeQuantity(decimal(n.get("quantity")));result.put(id(n.get("id")),s);}}
        var headers=current("SELECT * FROM mes_wm_product_sales WHERE tenant_id=? AND notice_id=?",tenant(),noticeId);
        if(!Set.of(3,4).contains(status(notice))&&!headers.isEmpty())return unreliable(result,"未审批通知存在关联出库历史，需核查后再处理");
        var headerIds=new LinkedHashSet<Long>();for(var h:headers)headerIds.add(id(h.get("id")));
        var lines=evidenceRows("mes_wm_product_sales_line",headerIds,"sales_id");
        if(lines.stream().anyMatch(l->!deleted(l)&&!noticeIds.contains(id(l.get("notice_line_id")))))return unreliable(result,"历史出库行跨通知关联或通知行缺失");
        var linked=evidenceRows("mes_wm_product_sales_line",noticeIds,"notice_line_id");
        // Do not acquire another notice's header after holding this notice mutex. Dirty cross-links are blocked immediately.
        if(linked.stream().anyMatch(l->!headerIds.contains(id(l.get("sales_id")))))return unreliable(result,"历史出库行跨通知关联或父单缺失");
        var byHeader=new HashMap<Long,Map<String,Object>>();for(var h:headers)byHeader.put(id(h.get("id")),h);
        var byLine=new HashMap<Long,Map<String,Object>>();for(var l:lines)byLine.put(id(l.get("id")),l);for(var l:linked)byLine.put(id(l.get("id")),l);
        var transactions=evidenceRows("mes_wm_transaction",headerIds,"biz_id");var lineTransactions=evidenceRows("mes_wm_transaction",byLine.keySet(),"biz_line_id");
        var byTransaction=new LinkedHashMap<Long,Map<String,Object>>();for(var t:transactions)if(Objects.equals(id(t.get("biz_type")),118L))byTransaction.put(id(t.get("id")),t);for(var t:lineTransactions)if(Objects.equals(id(t.get("biz_type")),118L))byTransaction.put(id(t.get("id")),t);
        var shippedByLine=new HashMap<Long,BigDecimal>();String warning=null;
        for(var t:byTransaction.values()){
            var line=byLine.get(id(t.get("biz_line_id")));var header=byHeader.get(id(t.get("biz_id")));var q=decimal(t.get("quantity"));
            if(line==null||header==null||!Objects.equals(id(line.get("sales_id")),id(t.get("biz_id")))||q==null||q.signum()>=0
                    ||deleted(t)||!Objects.equals(id(t.get("item_id")),id(line.get("item_id")))){warning="历史销售出库流水与单据归属或物料不一致";continue;}
            shippedByLine.merge(id(line.get("id")),q.negate(),BigDecimal::add);
            if(deleted(header)||deleted(line)||status(header)!=4||!Objects.equals(id(header.get("notice_id")),noticeId))warning="历史已发货证据存在删除、状态或通知关联异常";
            if(line.get("batch_id")!=null&&!Objects.equals(id(line.get("batch_id")),id(t.get("batch_id")))||line.get("batch_code")!=null&&!line.get("batch_code").toString().isBlank()&&!Objects.equals(line.get("batch_code"),t.get("batch_code")))warning="历史出库批次与指定批次不一致";
        }
        for(var h:headers){
            if(deleted(h)&&status(h)!=4)continue;
            if(!Objects.equals(id(h.get("notice_id")),noticeId)||!Objects.equals(id(h.get("client_id")),id(notice.get("client_id")))||!Set.of(0,1,2,3,4,5,10).contains(status(h)))warning="关联出库单的通知、客户或状态异常";
            if(status(h)==4&&byLine.values().stream().noneMatch(l->Objects.equals(id(l.get("sales_id")),id(h.get("id")))&&!deleted(l)))warning="已完成出库单缺少有效出库行";
        }
        for(var l:byLine.values()){
            var h=byHeader.get(id(l.get("sales_id")));BigDecimal posted=shippedByLine.getOrDefault(id(l.get("id")),BigDecimal.ZERO);
            if(deleted(l)||h==null||deleted(h)){if(posted.signum()>0||h!=null&&status(h)==4)warning="历史出库行或父单已删除或缺失";continue;}
            if(status(h)==5&&posted.signum()==0)continue;
            var s=result.get(id(l.get("notice_line_id")));var n=noticeLines.stream().filter(row->Objects.equals(id(row.get("id")),id(l.get("notice_line_id")))).findFirst().orElse(null);
            if(s==null||n==null||!Objects.equals(id(h.get("notice_id")),noticeId)||!Objects.equals(id(l.get("item_id")),id(n.get("item_id")))){warning="历史出库行与通知行归属或物料不一致";continue;}
            BigDecimal planned=decimal(l.get("quantity"));
            if(planned==null||planned.signum()<=0){warning="历史出库计划数量无效";continue;}
            s.setShippedQuantity(s.getShippedQuantity().add(posted));
            if(status(h)!=5)s.setReservedQuantity(s.getReservedQuantity().add(planned.subtract(posted).max(BigDecimal.ZERO)));
            if(status(h)==4&&posted.compareTo(planned)!=0||status(h)!=4&&posted.signum()>0)warning="出库完成状态与实际过账数量不一致";
            if(Set.of(2,3,4,10).contains(status(h))&&bool(l.get("oqc_check_flag"))&&(!Objects.equals(id(l.get("quality_status")),1L)||l.get("oqc_id")==null))warning="出库流程或完成状态与必检OQC结果不一致";
            if(!matches(l,n))warning="历史出库行不满足通知指定的批次或检验要求";
        }
        Map<String,Object> editing=editingSalesLineId==null?null:byLine.get(editingSalesLineId);
        if(editingSalesLineId!=null){var h=editing==null?null:byHeader.get(id(editing.get("sales_id")));
            if(editing==null||deleted(editing)||h==null||deleted(h)||status(h)!=0||!Objects.equals(id(h.get("notice_id")),noticeId))throw invalid("当前出库行不可编辑，请刷新后重试");
            if(shippedByLine.getOrDefault(editingSalesLineId,BigDecimal.ZERO).signum()>0){warning="草稿出库行已存在实际流水，不可继续编辑";editing=null;}}
        for(var s:result.values()){
            if(s.getNoticeQuantity()==null||s.getNoticeQuantity().signum()<=0||s.getShippedQuantity().add(s.getReservedQuantity()).compareTo(s.getNoticeQuantity())>0)warning="通知存在无效数量或历史累计占用超额";
            else s.setAvailableQuantity(s.getNoticeQuantity().subtract(s.getShippedQuantity()).subtract(s.getReservedQuantity()));
            s.setEditableAvailableQuantity(s.getAvailableQuantity());
        }
        if(status(notice)==4&&result.values().stream().anyMatch(s->s.getNoticeQuantity()==null||s.getShippedQuantity().compareTo(s.getNoticeQuantity())!=0))warning="通知完成状态与实际发货证据不一致";
        if(editing!=null&&result.containsKey(id(editing.get("notice_line_id")))){var s=result.get(id(editing.get("notice_line_id")));s.setEditableAvailableQuantity(s.getAvailableQuantity().add(decimal(editing.get("quantity"))));}
        for(var s:result.values()){
            s.setFulfillmentReliable(warning==null);s.setFulfillmentWarning(warning);
            if(warning!=null){s.setShippedQuantity(null);s.setReservedQuantity(null);s.setAvailableQuantity(BigDecimal.ZERO);s.setEditableAvailableQuantity(BigDecimal.ZERO);}
            s.setNoticeQuantity(scale(s.getNoticeQuantity()));s.setShippedQuantity(scale(s.getShippedQuantity()));s.setReservedQuantity(scale(s.getReservedQuantity()));s.setAvailableQuantity(scale(s.getAvailableQuantity()));s.setEditableAvailableQuantity(scale(s.getEditableAvailableQuantity()));
        }return result;
    }
    private Map<Long,Summary> unreliable(Map<Long,Summary> result,String warning){for(var s:result.values()){s.setFulfillmentReliable(false);s.setFulfillmentWarning(warning);s.setNoticeQuantity(scale(s.getNoticeQuantity()));s.setShippedQuantity(null);s.setReservedQuantity(null);s.setAvailableQuantity(scale(BigDecimal.ZERO));s.setEditableAvailableQuantity(scale(BigDecimal.ZERO));}return result;}
    private BigDecimal scale(BigDecimal q){return q==null?null:q.setScale(6);}
    private boolean matches(Map<String,Object> line,Map<String,Object> notice){
        return (notice.get("batch_id")==null||Objects.equals(id(notice.get("batch_id")),id(line.get("batch_id"))))
                &&(notice.get("batch_code")==null||notice.get("batch_code").toString().isBlank()||Objects.equals(notice.get("batch_code"),line.get("batch_code")))
                &&(notice.get("oqc_check_flag")==null||bool(notice.get("oqc_check_flag"))==bool(line.get("oqc_check_flag")));
    }
    public void validateAndReserve(MesWmProductSalesDO sales,Long excludedLineId,Long noticeLineId,Long itemId,BigDecimal quantity,String batchCode,Boolean oqcCheckFlag){
        requireQuantity(quantity);
        if(sales.getNoticeId()==null){if(noticeLineId!=null)throw exception(WM_PRODUCT_SALES_LINE_SALES_NOTICE_LINE_NOT_ALLOWED);return;}
        requireOpenNotice(sales.getNoticeId(),sales.getClientId());if(noticeLineId==null)throw exception(WM_PRODUCT_SALES_LINE_SALES_NOTICE_LINE_REQUIRED);
        var all=summaries(sales.getNoticeId(),excludedLineId);var s=all.get(noticeLineId);if(s==null)throw exception(WM_SALES_NOTICE_LINE_NOT_MATCH);
        if(!s.isFulfillmentReliable())throw invalid(s.getFulfillmentWarning());if(quantity.compareTo(s.getEditableAvailableQuantity())>0)throw invalid("出库数量超过通知行当前可用额度");
        var n=one("SELECT * FROM mes_wm_sales_notice_line WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE",noticeLineId,tenant());
        if(!Objects.equals(id(n.get("item_id")),itemId))throw exception(WM_PRODUCT_SALES_LINE_NOTICE_LINE_ITEM_MISMATCH);
        if(n.get("batch_code")!=null&&!n.get("batch_code").toString().isBlank()&&!Objects.equals(n.get("batch_code"),batchCode))throw exception(WM_PRODUCT_SALES_LINE_NOTICE_LINE_BATCH_MISMATCH);
        if(n.get("oqc_check_flag")!=null&&bool(n.get("oqc_check_flag"))!=Boolean.TRUE.equals(oqcCheckFlag))throw exception(WM_PRODUCT_SALES_LINE_NOTICE_LINE_OQC_MISMATCH);
    }
    public void validateSales(MesWmProductSalesDO sales){
        if(sales.getNoticeId()==null){if(sales.getId()!=null&&one("SELECT id FROM mes_wm_product_sales_line WHERE tenant_id=? AND sales_id=? AND notice_line_id IS NOT NULL AND deleted=0 LIMIT 1 FOR UPDATE",tenant(),sales.getId())!=null)throw exception(WM_PRODUCT_SALES_LINE_SALES_NOTICE_LINE_NOT_ALLOWED);return;}
        requireOpenNotice(sales.getNoticeId(),sales.getClientId());var sums=summaries(sales.getNoticeId(),null);
        if(sums.isEmpty())throw exception(WM_SALES_NOTICE_LINE_EMPTY);for(var s:sums.values())if(!s.isFulfillmentReliable())throw invalid(s.getFulfillmentWarning());
    }
    public void requireNoPosting(Long salesId){if(one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=118 AND biz_id=? LIMIT 1 FOR UPDATE",tenant(),salesId)!=null)throw invalid("已有出库流水的单据不可修改或取消");}
    /** Lock only this shipment's inspection scope, never an arbitrary inspection id from legacy line data. */
    public void validateCompletedOqc(MesWmProductSalesDO sales,List<MesWmProductSalesLineDO> lines){
        if(lines.stream().noneMatch(l->Boolean.TRUE.equals(l.getOqcCheckFlag())))return;
        var inspections=current("SELECT * FROM mes_qc_oqc WHERE tenant_id=? AND source_doc_type=118 AND source_doc_id=?",tenant(),sales.getId());
        for(var line:lines){if(!Boolean.TRUE.equals(line.getOqcCheckFlag()))continue;
            var row=inspections.stream().filter(q->Objects.equals(id(q.get("id")),line.getOqcId())).findFirst().orElse(null);
            if(row==null||deleted(row)||status(row)!=4||!Objects.equals(id(row.get("check_result")),1L)
                    ||!Objects.equals(id(row.get("source_line_id")),line.getId())||!Objects.equals(id(row.get("item_id")),line.getItemId())
                    ||!Objects.equals(id(row.get("client_id")),sales.getClientId())||decimal(row.get("out_quantity"))==null
                    ||decimal(row.get("out_quantity")).compareTo(line.getQuantity())!=0||!Objects.equals(batch(row.get("batch_code")),batch(line.getBatchCode())))
                throw invalid("必检OQC的已完成凭据与本次出库来源、客户、物料、批次或数量不一致");
        }
    }
    private String batch(Object value){return value==null||value.toString().isBlank()?null:value.toString();}
    public void refreshNotice(Long noticeId){if(noticeId==null)return;var sums=summaries(noticeId,null);if(sums.isEmpty())return;for(var s:sums.values())if(!s.isFulfillmentReliable())throw invalid(s.getFulfillmentWarning());
        if(sums.values().stream().allMatch(s->s.getShippedQuantity().compareTo(s.getNoticeQuantity())==0))jdbcTemplate.update("UPDATE mes_wm_sales_notice SET status=4 WHERE id=? AND tenant_id=? AND deleted=0",noticeId,tenant());}
}
