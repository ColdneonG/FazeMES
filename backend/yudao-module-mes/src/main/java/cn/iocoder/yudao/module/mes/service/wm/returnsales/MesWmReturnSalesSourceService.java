package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/** Dedicated source mutexes protect physical return capacity without retaining historical shipment locks. */
@Service
public class MesWmReturnSalesSourceService {
    @Resource private JdbcTemplate jdbcTemplate;
    @Resource private MesWmReturnSalesMapper returnSalesMapper;
    @Resource private MesWmReturnSalesLineMapper returnSalesLineMapper;
    @Resource private MesWmReturnSalesDeliveryService deliveryService;
    @Data public static class Balance {
        private BigDecimal outboundQuantity,returnedQuantity=BigDecimal.ZERO,reservedQuantity=BigDecimal.ZERO,
                availableQuantity=BigDecimal.ZERO,editableAvailableQuantity=BigDecimal.ZERO;
        private boolean sourceReliable=true;
        private String sourceWarning;
    }
    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private ServiceException invalid(String text){return new ServiceException(1_040_713_009,text);}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbcTemplate.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private List<Map<String,Object>> rows(String sql,boolean current,Object...args){return jdbcTemplate.queryForList(sql+(current?" FOR UPDATE":""),args);}
    private Long id(Object value){return value==null?null:((Number)value).longValue();}
    private BigDecimal qty(Object value){return value==null?null:new BigDecimal(value.toString());}
    private boolean deleted(Map<String,Object> row){Object v=row.get("deleted");return Boolean.TRUE.equals(v)||(v instanceof Number&&((Number)v).intValue()!=0);}
    private String code(Object v){return v==null||v.toString().isBlank()?null:v.toString();}
    private boolean same(Object a,Object b){return Objects.equals(a,b);}
    private BigDecimal scale(BigDecimal q){return q==null?null:q.setScale(6);}
    public void requireQuantity(BigDecimal q){if(q==null||q.signum()<=0||q.scale()>6||q.precision()-q.scale()>14)throw invalid("退货数量必须大于零，最多14位整数和6位小数");}

    public MesWmReturnSalesDO lockParent(Long id){var p=returnSalesMapper.selectForUpdate(id);if(p==null)throw exception(WM_RETURN_SALES_NOT_EXISTS);return p;}
    public void lockOriginIds(Collection<Long> ids){
        var sorted=new TreeSet<Long>();for(Long value:ids)if(value!=null)sorted.add(value);
        for(Long origin:sorted){
            jdbcTemplate.update("INSERT IGNORE INTO mes_wm_return_source_budget(tenant_id,origin_transaction_id) VALUES(?,?)",tenant(),origin);
            one("SELECT origin_transaction_id FROM mes_wm_return_source_budget WHERE tenant_id=? AND origin_transaction_id=? FOR UPDATE",tenant(),origin);
        }
    }
    /** Snapshot identifiers may conservatively be stale; never acquire an extra lower-ranked mutex after current child locks. */
    public List<MesWmReturnSalesLineDO> lockDocumentSources(Long returnId){
        lockParent(returnId);
        var located=returnSalesLineMapper.selectListByReturnId(returnId);
        var ids=new TreeSet<Long>();for(var line:located)if(line.getOriginTransactionId()!=null)ids.add(line.getOriginTransactionId());
        lockOriginIds(ids);
        jdbcTemplate.queryForList("SELECT id FROM mes_qc_rqc WHERE tenant_id=? AND source_doc_type=119 AND source_doc_id=? ORDER BY id FOR UPDATE",tenant(),returnId);
        var current=returnSalesLineMapper.selectListByReturnIdForUpdate(returnId);
        for(var line:current)if(line.getOriginTransactionId()!=null&&!ids.contains(line.getOriginTransactionId()))throw invalid("退货来源已改变，请刷新后重试");
        return current;
    }
    public Long lockForRqc(Long returnId,Long lineId){
        lockParent(returnId);var located=returnSalesLineMapper.selectById(lineId);
        if(located==null||!same(located.getReturnId(),returnId))throw invalid("RQC来源行不属于指定退货单");
        lockOriginIds(Collections.singleton(located.getOriginTransactionId()));return located.getOriginTransactionId();
    }
    public void validateForRqc(Long returnId,Long lineId,Long lockedOrigin){
        var parent=lockParent(returnId);var line=returnSalesLineMapper.selectByIdForUpdate(lineId);
        if(line==null||!same(line.getReturnId(),returnId)||!same(line.getOriginTransactionId(),lockedOrigin))throw invalid("RQC退货来源已改变，请刷新后重试");
        validateLine(parent,line);
    }

    /** Ordinary reads of immutable FINISHED origin. No original header/118/allocation lock survives into stock. */
    public Map<String,Object> origin(Long clientId,Long originId){
        if(originId==null)throw invalid("请选择原销售出库流水；历史来源不自动猜配");
        var row=one("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND id=?",tenant(),originId);
        if(row==null||deleted(row)||!same(id(row.get("biz_type")),118L)||!same(id(row.get("type")),2L)||qty(row.get("quantity"))==null||qty(row.get("quantity")).signum()>=0)throw invalid("原销售出库流水不存在或不是有效负118凭据");
        var sales=one("SELECT * FROM mes_wm_product_sales WHERE tenant_id=? AND id=?",tenant(),row.get("biz_id"));
        var line=one("SELECT * FROM mes_wm_product_sales_line WHERE tenant_id=? AND id=?",tenant(),row.get("biz_line_id"));
        if(sales==null||line==null||deleted(sales)||deleted(line)||!same(id(sales.get("status")),4L)
                ||!same(id(sales.get("client_id")),clientId)||!same(id(line.get("sales_id")),id(sales.get("id")))
                ||!same(id(line.get("item_id")),id(row.get("item_id")))||!same(id(line.get("quality_status")),1L))throw invalid("原发货客户、物料或所属单据不一致");
        deliveryService.validateDelivery(clientId,id(row.get("item_id")),id(row.get("batch_id")));
        deliveryService.validateBatchCode(id(row.get("item_id")),id(row.get("batch_id")),code(row.get("batch_code")));
        line.put("line_id",line.get("id"));
        if(!deliveryService.matchesActualPosting(tenant(),id(row.get("item_id")),id(row.get("batch_id")),code(row.get("batch_code")),line))throw invalid("原销售行与实际拣货/扣库凭据不一致");
        row.put("sales_code",sales.get("code"));row.put("client_id",clientId);return row;
    }

    private String legacyWarning(Long client,Long item,Long batch){
        // Includes deleted/canceled physical evidence. Missing parent/line attribution broadens the hold instead of freeing quota.
        var unknown=jdbcTemplate.queryForList("""
                SELECT t.id,t.biz_id,r.code FROM mes_wm_transaction t
                LEFT JOIN mes_wm_return_sales r ON r.id=t.biz_id AND r.tenant_id=t.tenant_id
                LEFT JOIN mes_wm_return_sales_line l ON l.id=t.biz_line_id AND l.return_id=t.biz_id AND l.tenant_id=t.tenant_id
                LEFT JOIN mes_wm_transaction o ON o.id=t.return_source_transaction_id AND o.tenant_id=t.tenant_id
                LEFT JOIN mes_wm_product_sales s ON s.id=o.biz_id AND s.tenant_id=o.tenant_id
                WHERE t.tenant_id=? AND t.biz_type=119 AND (t.return_source_transaction_id IS NULL OR o.id IS NULL OR r.id IS NULL OR l.id IS NULL OR r.client_id IS NULL
                    OR o.biz_type<>118 OR o.type<>2 OR o.quantity>=0 OR o.deleted<>0 OR s.id IS NULL OR s.status<>4 OR s.deleted<>0
                    OR s.client_id<>r.client_id OR o.item_id<>t.item_id OR (o.batch_id IS NULL AND t.batch_id IS NOT NULL)
                    OR (o.batch_id IS NOT NULL AND t.batch_id IS NULL) OR o.batch_id<>t.batch_id)
                  AND (r.id IS NULL OR l.id IS NULL OR r.client_id IS NULL OR t.item_id IS NULL
                    OR (r.client_id=? AND t.item_id=? AND (t.batch_id=? OR (t.batch_id IS NULL AND ? IS NULL))))
                LIMIT 1
                """,tenant(),client,item,batch,batch);
        if(!unknown.isEmpty())return "历史退货["+unknown.get(0).get("biz_id")+"]缺少原出库关联，可能占用可退额度。请人工核对原销售及退货凭证并保留审计记录；不得猜配或修改旧流水，本来源暂不可退。";
        var missing=jdbcTemplate.queryForList("""
                SELECT r.id FROM mes_wm_return_sales r LEFT JOIN mes_wm_return_sales_line l ON l.return_id=r.id AND l.tenant_id=r.tenant_id
                WHERE r.tenant_id=? AND r.status=4 AND (l.id IS NULL OR l.origin_transaction_id IS NULL OR NOT EXISTS(
                    SELECT 1 FROM mes_wm_transaction t WHERE t.tenant_id=r.tenant_id AND t.biz_type=119 AND t.biz_id=r.id
                    AND t.biz_line_id=l.id AND t.quantity>0 AND t.return_source_transaction_id=l.origin_transaction_id))
                  AND (r.client_id IS NULL OR l.id IS NULL OR l.item_id IS NULL OR (r.client_id=? AND l.item_id=? AND (l.batch_id=? OR (l.batch_id IS NULL AND ? IS NULL))))
                LIMIT 1
                """,tenant(),client,item,batch,batch);
        return missing.isEmpty()?null:"历史已完成退货["+missing.get(0).get("id")+"]来源无法核定。请核对原销售、实际退货和库存凭证，保留审计记录后处理；本来源额度暂不可确定。";
    }
    private Balance unreliable(Balance b,String warning){b.setSourceReliable(false);b.setSourceWarning(warning);b.setReturnedQuantity(null);b.setReservedQuantity(null);b.setAvailableQuantity(BigDecimal.ZERO);b.setEditableAvailableQuantity(BigDecimal.ZERO);return b;}
    private Balance balance(Map<String,Object> source,Long editingLine,Long postingReturn,boolean current){
        var b=new Balance();b.setOutboundQuantity(qty(source.get("quantity")).negate());
        String legacy=legacyWarning(id(source.get("client_id")),id(source.get("item_id")),id(source.get("batch_id")));
        if(legacy!=null)return unreliable(b,legacy);
        Long originId=id(source.get("id"));
        var posted=rows("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND return_source_transaction_id=? ORDER BY id",false,tenant(),originId);
        var reservations=rows("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND origin_transaction_id=? ORDER BY return_line_id",false,tenant(),originId);
        var budget=one("SELECT * FROM mes_wm_return_source_budget WHERE tenant_id=? AND origin_transaction_id=?"+(current?" FOR UPDATE":""),tenant(),originId);
        if(budget==null)return unreliable(b,"来源额度记录尚未初始化，请重新打开来源选择");
        var perLine=new HashMap<Long,BigDecimal>();
        for(var t:posted){
            BigDecimal q=qty(t.get("quantity"));
            if(deleted(t)||q==null||q.signum()<=0||!same(id(t.get("type")),1L)||!same(id(t.get("item_id")),id(source.get("item_id")))||!same(id(t.get("batch_id")),id(source.get("batch_id")))||!same(code(t.get("batch_code")),code(source.get("batch_code"))))return unreliable(b,"已退货流水的物料、批次或方向异常，额度已锁定待核对");
            perLine.merge(id(t.get("biz_line_id")),q,BigDecimal::add);b.setReturnedQuantity(b.getReturnedQuantity().add(q));
            var parent=one("SELECT * FROM mes_wm_return_sales WHERE tenant_id=? AND id=?",tenant(),t.get("biz_id"));
            var line=one("SELECT * FROM mes_wm_return_sales_line WHERE tenant_id=? AND id=?",tenant(),t.get("biz_line_id"));
            if(parent==null||line==null||deleted(parent)||deleted(line)||!same(id(parent.get("client_id")),id(source.get("client_id")))
                    ||!(same(id(parent.get("status")),4L)||same(id(parent.get("id")),postingReturn)&&same(id(parent.get("status")),3L))
                    ||!same(id(line.get("return_id")),id(parent.get("id")))||!same(id(line.get("origin_transaction_id")),originId)
                    ||!same(id(line.get("item_id")),id(source.get("item_id")))||!same(id(line.get("batch_id")),id(source.get("batch_id"))))return unreliable(b,"退货入库所属父单、客户、状态或行来源不一致，额度暂不可核定");
        }
        var seen=new HashSet<Long>();BigDecimal own=BigDecimal.ZERO;
        for(var r:reservations){
            Long lineId=id(r.get("return_line_id"));seen.add(lineId);BigDecimal q=qty(r.get("quantity")),actual=qty(r.get("posted_quantity"));
            if(actual==null||actual.signum()<0||actual.compareTo(perLine.getOrDefault(lineId,BigDecimal.ZERO))!=0)return unreliable(b,"来源已入库计数与119不可变凭据不一致，可能为旧快照，请刷新核对后重试");
            String state=String.valueOf(r.get("state"));
            if(q==null||q.signum()<0||(q.signum()==0&&!same(lineId,editingLine)&&!"RELEASED".equals(state))||actual.compareTo(q)>0||!Set.of("RESERVED","POSTED","RELEASED").contains(state))return unreliable(b,"退货预留记录数量或状态异常");
            if("RELEASED".equals(state)){if(actual.signum()!=0)return unreliable(b,"已释放退货来源仍有库存流水");continue;}
            if("POSTED".equals(state)&&actual.compareTo(q)!=0)return unreliable(b,"退货完成数量与实际入库凭据不一致");
            if("RESERVED".equals(state)){BigDecimal rest=q.subtract(actual);b.setReservedQuantity(b.getReservedQuantity().add(rest));if(same(lineId,editingLine)&&actual.signum()==0)own=rest;}
            for(var t:posted)if(same(id(t.get("biz_line_id")),lineId)&&!same(id(t.get("biz_id")),id(r.get("return_id"))))return unreliable(b,"退货流水与来源预留父单不一致");
        }
        if(!seen.containsAll(perLine.keySet()))return unreliable(b,"退货入库流水缺少来源预留凭据，额度无法核定");
        for(var t:posted){var evidence=one("SELECT * FROM mes_wm_return_posted_evidence WHERE tenant_id=? AND return_transaction_id=?",tenant(),t.get("id"));if(evidence==null||!same(id(evidence.get("return_id")),id(t.get("biz_id")))||!same(id(evidence.get("return_line_id")),id(t.get("biz_line_id")))||!same(id(evidence.get("origin_transaction_id")),originId)||!same(id(evidence.get("return_detail_id")),id(t.get("return_detail_id")))||qty(evidence.get("quantity")).compareTo(qty(t.get("quantity")))!=0)return unreliable(b,"退货入库来源幂等凭据缺失或归属不一致，不能再次计算或释放额度");}
        BigDecimal authoritativeReserved=qty(budget.get("reserved_quantity")),authoritativeReturned=qty(budget.get("returned_quantity"));
        if(authoritativeReserved==null||authoritativeReturned==null||authoritativeReserved.signum()<0||authoritativeReturned.signum()<0
                ||authoritativeReserved.compareTo(b.getReservedQuantity())!=0||authoritativeReturned.compareTo(b.getReturnedQuantity())!=0)return unreliable(b,"来源额度当前计数与不可变凭据快照不一致，请刷新核对后重试");
        b.setReservedQuantity(authoritativeReserved);b.setReturnedQuantity(authoritativeReturned);
        BigDecimal used=b.getReturnedQuantity().add(b.getReservedQuantity());
        if(used.compareTo(b.getOutboundQuantity())>0)return unreliable(b,"累计退货和预留已经超过原发货数量");
        b.setAvailableQuantity(b.getOutboundQuantity().subtract(used));b.setEditableAvailableQuantity(b.getAvailableQuantity().add(own));return b;
    }
    public void reserve(MesWmReturnSalesDO parent,MesWmReturnSalesLineDO line){
        requireQuantity(line.getQuantity());var source=origin(parent.getClientId(),line.getOriginTransactionId());
        requireIdentity(line,source);
        jdbcTemplate.update("INSERT IGNORE INTO mes_wm_return_source_reservation(tenant_id,return_line_id,return_id,origin_transaction_id,quantity,posted_quantity,state) VALUES(?,?,?,?,0,0,'RESERVED')",tenant(),line.getId(),parent.getId(),line.getOriginTransactionId());
        var own=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),line.getId());
        if(qty(own.get("posted_quantity")).signum()!=0||"POSTED".equals(own.get("state")))throw invalid("已实际退货的来源不能重置预留");
        if("RESERVED".equals(own.get("state"))&&!same(id(own.get("origin_transaction_id")),line.getOriginTransactionId()))throw invalid("更换来源前必须释放原预留");
        var b=balance(source,line.getId(),null,true);
        if(!b.isSourceReliable())throw invalid(b.getSourceWarning());if(line.getQuantity().compareTo(b.getEditableAvailableQuantity())>0)throw invalid("退货数量超过原出库流水当前可退额度");
        BigDecimal old="RESERVED".equals(own.get("state"))?qty(own.get("quantity")):BigDecimal.ZERO;
        int changed=jdbcTemplate.update("UPDATE mes_wm_return_source_budget SET reserved_quantity=reserved_quantity+?-? WHERE tenant_id=? AND origin_transaction_id=? AND reserved_quantity+?-?+returned_quantity<=? AND reserved_quantity+?-?>=0",line.getQuantity(),old,tenant(),line.getOriginTransactionId(),line.getQuantity(),old,b.getOutboundQuantity(),line.getQuantity(),old);
        if(changed!=1)throw invalid("退货来源额度已变化，请刷新");
        jdbcTemplate.update("INSERT INTO mes_wm_return_source_reservation(tenant_id,return_line_id,return_id,origin_transaction_id,quantity,state) VALUES(?,?,?,?,?,'RESERVED') ON DUPLICATE KEY UPDATE return_id=VALUES(return_id),origin_transaction_id=VALUES(origin_transaction_id),quantity=VALUES(quantity),state='RESERVED',update_time=CURRENT_TIMESTAMP",tenant(),line.getId(),parent.getId(),line.getOriginTransactionId(),line.getQuantity());
    }
    private void requireIdentity(MesWmReturnSalesLineDO line,Map<String,Object> source){if(!same(line.getItemId(),id(source.get("item_id")))||!same(line.getBatchId(),id(source.get("batch_id")))||!same(code(line.getBatchCode()),code(source.get("batch_code"))))throw invalid("退货行物料/批次与原出库流水不一致");}
    public void validateLine(MesWmReturnSalesDO parent,MesWmReturnSalesLineDO line){
        requireQuantity(line.getQuantity());var source=origin(parent.getClientId(),line.getOriginTransactionId());requireIdentity(line,source);
        var reservation=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),line.getId());
        if(reservation==null||!same(id(reservation.get("return_id")),parent.getId())||!same(id(reservation.get("origin_transaction_id")),line.getOriginTransactionId())||qty(reservation.get("quantity")).compareTo(line.getQuantity())!=0||"RELEASED".equals(reservation.get("state")))throw invalid("退货行缺少一致的原出库额度预留；历史未执行单请取消后重新选择原发货来源");
        var b=balance(source,null,parent.getId(),true);if(!b.isSourceReliable())throw invalid(b.getSourceWarning());
    }
    public void requireNoPosting(Long returnId){if(one("SELECT return_line_id FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_id=? AND posted_quantity>0 LIMIT 1",tenant(),returnId)!=null || one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND biz_id=? LIMIT 1",tenant(),returnId)!=null)throw invalid("已有退货入库流水，不可重试整单过账、修改或释放额度");}
    public void releaseLine(Long lineId){
        var line=returnSalesLineMapper.selectByIdForUpdate(lineId);
        if(line==null)throw invalid("退货行不存在，不能释放来源");
        jdbcTemplate.update("INSERT IGNORE INTO mes_wm_return_source_reservation(tenant_id,return_line_id,return_id,origin_transaction_id,quantity,posted_quantity,state) VALUES(?,?,?,?,0,0,'RELEASED')",tenant(),lineId,line.getReturnId(),line.getOriginTransactionId()==null?0L:line.getOriginTransactionId());
        var r=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),lineId);
        if(!"RELEASED".equals(r.get("state"))&&!same(id(r.get("origin_transaction_id")),line.getOriginTransactionId()))throw invalid("退货预留原来源与当前行不一致，不得释放其它来源额度");
        if(qty(r.get("posted_quantity")).signum()>0||"POSTED".equals(r.get("state"))||one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND biz_line_id=? LIMIT 1",tenant(),lineId)!=null)throw invalid("已入库退货不能释放来源额度");
        if("RESERVED".equals(r.get("state"))){int changed=jdbcTemplate.update("UPDATE mes_wm_return_source_budget SET reserved_quantity=reserved_quantity-? WHERE tenant_id=? AND origin_transaction_id=? AND reserved_quantity>=?",qty(r.get("quantity")),tenant(),r.get("origin_transaction_id"),qty(r.get("quantity")));if(changed!=1)throw invalid("来源预留计数异常，不得释放");}
        jdbcTemplate.update("UPDATE mes_wm_return_source_reservation SET state='RELEASED',update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND return_line_id=?",tenant(),lineId);
    }
    public void markPosted(Long returnId,Collection<Long> currentLineIds){
        for(Long lineId:new TreeSet<>(currentLineIds)){var row=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),lineId);if(row==null||!same(id(row.get("return_id")),returnId)||!"RESERVED".equals(row.get("state"))||qty(row.get("quantity")).compareTo(qty(row.get("posted_quantity")))!=0)throw invalid("退货实际入库尚未完成，不能结束或释放额度");jdbcTemplate.update("UPDATE mes_wm_return_source_reservation SET state='POSTED',update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND return_line_id=?",tenant(),lineId);}
    }
    public void splitReservation(MesWmReturnSalesDO parent,MesWmReturnSalesLineDO original,MesWmReturnSalesLineDO split){
        var old=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),original.getId());
        BigDecimal removed=qty(old.get("quantity")).subtract(original.getQuantity());
        jdbcTemplate.update("UPDATE mes_wm_return_source_budget SET reserved_quantity=reserved_quantity-? WHERE tenant_id=? AND origin_transaction_id=?",removed,tenant(),original.getOriginTransactionId());
        jdbcTemplate.update("UPDATE mes_wm_return_source_reservation SET quantity=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND return_line_id=? AND state='RESERVED'",original.getQuantity(),tenant(),original.getId());
        reserve(parent,split);
    }

    /** Must run before the transaction service's area lock loop, including callers other than the return screen. */
    public void preflight(List<MesWmTransactionSaveReqDTO> requests){
        var returns=new TreeSet<Long>();for(var req:requests){if(!same(req.getBizType(),119)){if(req.getReturnSourceTransactionId()!=null||req.getReturnDetailId()!=null)throw invalid("仅销售退货119允许原销售出库和上架明细来源");}else{if(req.getReturnSourceTransactionId()==null||req.getReturnDetailId()==null||req.getRelatedTransactionId()!=null)throw invalid("销售退货必须提供独立原出库来源及实际上架明细，不能用成对调拨关联代替");returns.add(req.getBizId());}}
        if(returns.size()>1)throw invalid("一次退货过账只能属于同一父单");
        if(!returns.isEmpty()&&requests.stream().anyMatch(r->!same(r.getBizType(),119)))throw invalid("销售退货不能与其它业务混合过账");
        if(returns.isEmpty())return;var parent=lockParent(returns.first());
        if(!same(parent.getStatus(),3))throw invalid("仅待上架退货单可生成119入库流水");
        var lines=lockDocumentSources(parent.getId());var byId=new HashMap<Long,MesWmReturnSalesLineDO>();for(var line:lines)byId.put(line.getId(),line);
        var details=jdbcTemplate.queryForList("SELECT * FROM mes_wm_return_sales_detail WHERE tenant_id=? AND return_id=? AND deleted=0 ORDER BY id FOR UPDATE",tenant(),parent.getId());
        var seenDetails=new HashSet<Long>();
        var requested=new HashMap<Long,BigDecimal>();
        for(var req:requests){if(!same(req.getBizType(),119))continue;var line=byId.get(req.getBizLineId());
            requireQuantity(req.getQuantity());
            if(line==null||!same(line.getOriginTransactionId(),req.getReturnSourceTransactionId())||!same(line.getItemId(),req.getItemId())||!same(line.getBatchId(),req.getBatchId())||!same(code(line.getBatchCode()),code(req.getBatchCode()))||!same(req.getType(),1)||!same(parent.getCode(),req.getBizCode()))throw invalid("119入库来源与已锁退货单行不一致");
            var detail=details.stream().filter(d->same(id(d.get("id")),req.getReturnDetailId())).findFirst().orElse(null);
            if(detail==null||!seenDetails.add(req.getReturnDetailId())||!same(id(detail.get("line_id")),line.getId())||!same(id(detail.get("item_id")),req.getItemId())||!same(id(detail.get("batch_id")),req.getBatchId())||!same(code(detail.get("batch_code")),code(req.getBatchCode()))||!same(id(detail.get("warehouse_id")),req.getWarehouseId())||!same(id(detail.get("location_id")),req.getLocationId())||!same(id(detail.get("area_id")),req.getAreaId())||qty(detail.get("quantity")).compareTo(req.getQuantity())!=0)throw invalid("119必须逐条对应本单已校验的实际上架明细");
            if(one("SELECT id FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND return_detail_id=? LIMIT 1",tenant(),req.getReturnDetailId())!=null)throw invalid("该退货上架明细已经入库，不能重复执行");
            requireQuantity(req.getQuantity());validateLine(parent,line);requested.merge(line.getId(),req.getQuantity(),BigDecimal::add);
        }
        for(var entry:requested.entrySet()){
            var r=one("SELECT posted_quantity FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),entry.getKey());
            BigDecimal already=qty(r.get("posted_quantity"));
            if(already.add(entry.getValue()).compareTo(byId.get(entry.getKey()).getQuantity())>0)throw invalid("本次入库将超过退货行预留数量");
        }
    }

    public void validateWarehouse(MesWmTransactionSaveReqDTO req,cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO warehouse){
        if(!same(req.getBizType(),119))return;
        var line=returnSalesLineMapper.selectByIdForUpdate(req.getBizLineId());
        if(line==null||!same(line.getReturnId(),req.getBizId()))throw invalid("退货行归属变化");
        cn.iocoder.yudao.module.mes.service.wm.quality.MesWmQualityPolicy.validateInbound(line.getQualityStatus(),warehouse);
    }

    @Transactional(rollbackFor=Exception.class)
    public void recordPosted(cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO transaction){
        if(!same(transaction.getBizType(),119))return;
        requireQuantity(transaction.getQuantity());
        var persisted=one("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND id=?",tenant(),transaction.getId());
        if(persisted==null||deleted(persisted)||!same(id(persisted.get("biz_type")),119L)||!same(id(persisted.get("type")),1L)
                ||!same(id(persisted.get("biz_id")),transaction.getBizId())||!same(id(persisted.get("biz_line_id")),transaction.getBizLineId())||!same(id(persisted.get("return_source_transaction_id")),transaction.getReturnSourceTransactionId())||!same(id(persisted.get("return_detail_id")),transaction.getReturnDetailId())||transaction.getReturnDetailId()==null||qty(persisted.get("quantity")).compareTo(transaction.getQuantity())!=0)throw invalid("来源计数必须来自已成功插入的本次119实际流水");
        lockParent(transaction.getBizId());lockOriginIds(Collections.singleton(transaction.getReturnSourceTransactionId()));
        var priorEvidence=one("SELECT * FROM mes_wm_return_posted_evidence WHERE tenant_id=? AND return_transaction_id=?",tenant(),transaction.getId());
        if(priorEvidence!=null){if(!same(id(priorEvidence.get("return_line_id")),transaction.getBizLineId())||!same(id(priorEvidence.get("origin_transaction_id")),transaction.getReturnSourceTransactionId())||!same(id(priorEvidence.get("return_detail_id")),transaction.getReturnDetailId())||qty(priorEvidence.get("quantity")).compareTo(transaction.getQuantity())!=0)throw invalid("119重复计数内容与原凭据不一致");return;}
        var reservation=one("SELECT * FROM mes_wm_return_source_reservation WHERE tenant_id=? AND return_line_id=? FOR UPDATE",tenant(),transaction.getBizLineId());
        BigDecimal previousActual=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM mes_wm_transaction WHERE tenant_id=? AND biz_type=119 AND biz_line_id=? AND id<>?",BigDecimal.class,tenant(),transaction.getBizLineId(),transaction.getId());
        BigDecimal previousEvidence=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM mes_wm_return_posted_evidence WHERE tenant_id=? AND return_line_id=?",BigDecimal.class,tenant(),transaction.getBizLineId());
        if(reservation==null||!"RESERVED".equals(reservation.get("state"))||qty(reservation.get("posted_quantity")).compareTo(previousActual)!=0||previousActual.compareTo(previousEvidence)!=0)throw invalid("119实际流水、当前计数或旧幂等凭据不一致，不得重新计数");
        int recorded=jdbcTemplate.update("INSERT IGNORE INTO mes_wm_return_posted_evidence(tenant_id,return_transaction_id,return_id,return_line_id,return_detail_id,origin_transaction_id,quantity) VALUES(?,?,?,?,?,?,?)",tenant(),transaction.getId(),transaction.getBizId(),transaction.getBizLineId(),transaction.getReturnDetailId(),transaction.getReturnSourceTransactionId(),transaction.getQuantity());
        if(recorded==0){var prior=one("SELECT * FROM mes_wm_return_posted_evidence WHERE tenant_id=? AND return_transaction_id=?",tenant(),transaction.getId());if(prior==null||!same(id(prior.get("return_line_id")),transaction.getBizLineId())||!same(id(prior.get("origin_transaction_id")),transaction.getReturnSourceTransactionId())||!same(id(prior.get("return_detail_id")),transaction.getReturnDetailId())||qty(prior.get("quantity")).compareTo(transaction.getQuantity())!=0)throw invalid("119重复计数内容与原凭据不一致");return;}
        int changed=jdbcTemplate.update("UPDATE mes_wm_return_source_reservation SET posted_quantity=posted_quantity+?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND return_id=? AND return_line_id=? AND origin_transaction_id=? AND state='RESERVED' AND posted_quantity+?<=quantity",transaction.getQuantity(),tenant(),transaction.getBizId(),transaction.getBizLineId(),transaction.getReturnSourceTransactionId(),transaction.getQuantity());
        if(changed!=1)throw invalid("119入库超过原出库来源预留或记录已变化");
        changed=jdbcTemplate.update("UPDATE mes_wm_return_source_budget SET reserved_quantity=reserved_quantity-?,returned_quantity=returned_quantity+? WHERE tenant_id=? AND origin_transaction_id=? AND reserved_quantity>=?",transaction.getQuantity(),transaction.getQuantity(),tenant(),transaction.getReturnSourceTransactionId(),transaction.getQuantity());
        if(changed!=1)throw invalid("119来源当前额度计数异常");
    }

    @Transactional(rollbackFor=Exception.class)
    public PageResult<MesWmReturnSalesSourceRespVO> options(MesWmReturnSalesSourcePageReqVO req){
        var parent=lockParent(req.getReturnId());if(!same(parent.getStatus(),0))throw invalid("只能为草稿退货单选择原发货来源");
        if(req.getPageNo()<1||req.getPageSize()<1||req.getPageSize()>50)throw invalid("来源页码必须为正数，每页数量须为1至50");
        MesWmReturnSalesLineDO editing=null;if(req.getLineId()!=null){editing=returnSalesLineMapper.selectById(req.getLineId());if(editing==null||!same(editing.getReturnId(),parent.getId()))throw invalid("编辑行不属于当前退货单");}
        var args=new ArrayList<Object>();args.add(tenant());args.add(parent.getClientId());
        String sql="SELECT t.id FROM mes_wm_transaction t JOIN mes_wm_product_sales s ON s.id=t.biz_id AND s.tenant_id=t.tenant_id JOIN mes_md_item i ON i.id=t.item_id AND i.tenant_id=t.tenant_id WHERE t.tenant_id=? AND s.client_id=? AND s.status=4 AND s.deleted=0 AND t.biz_type=118 AND t.type=2 AND t.quantity<0 AND t.deleted=0";
        if(req.getOriginTransactionId()!=null){sql+=" AND t.id=?";args.add(req.getOriginTransactionId());}
        for(var f:List.of(new String[]{"s.code",req.getSalesCode()},new String[]{"i.code",req.getItemCode()},new String[]{"t.batch_code",req.getBatchCode()}))if(f[1]!=null&&!f[1].isBlank()){sql+=" AND "+f[0]+" LIKE ? ESCAPE '!'";args.add("%"+f[1].trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        Long total=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ("+sql+") matched_sources",Long.class,args.toArray());
        long offset=((long)req.getPageNo()-1L)*req.getPageSize();
        var pageArgs=new ArrayList<Object>(args);pageArgs.add(req.getPageSize());pageArgs.add(offset);
        var pageIds=jdbcTemplate.queryForList(sql+" ORDER BY t.id DESC LIMIT ? OFFSET ?",Long.class,pageArgs.toArray());
        var mutexes=new TreeSet<Long>(pageIds);if(editing!=null&&editing.getOriginTransactionId()!=null)mutexes.add(editing.getOriginTransactionId());lockOriginIds(mutexes);
        if(editing!=null){var current=returnSalesLineMapper.selectByIdForUpdate(editing.getId());if(current==null||!same(current.getReturnId(),parent.getId())||!same(current.getOriginTransactionId(),editing.getOriginTransactionId()))throw invalid("编辑来源已改变，请刷新");}
        var list=new ArrayList<MesWmReturnSalesSourceRespVO>();for(var sourceId:pageIds){try{var src=origin(parent.getClientId(),sourceId);var b=balance(src,editing==null?null:editing.getId(),null,true);list.add(option(src,b));}catch(ServiceException ex){var raw=one("SELECT * FROM mes_wm_transaction WHERE tenant_id=? AND id=?",tenant(),sourceId);if(raw!=null){raw.put("sales_code",null);var b=new Balance();b.setOutboundQuantity(qty(raw.get("quantity")).abs());list.add(option(raw,unreliable(b,ex.getMessage())));}}}
        return new PageResult<>(list,total);
    }
    private MesWmReturnSalesSourceRespVO option(Map<String,Object> s,Balance b){
        var v=new MesWmReturnSalesSourceRespVO();v.setId(id(s.get("id")));v.setOriginTransactionId(v.getId());v.setSalesId(id(s.get("biz_id")));v.setSalesLineId(id(s.get("biz_line_id")));v.setSalesCode(code(s.get("sales_code")));v.setItemId(id(s.get("item_id")));v.setBatchId(id(s.get("batch_id")));v.setBatchCode(code(s.get("batch_code")));v.setMaterialStockId(id(s.get("material_stock_id")));v.setWarehouseId(id(s.get("warehouse_id")));v.setLocationId(id(s.get("location_id")));v.setAreaId(id(s.get("area_id")));
        Object at=s.get("transaction_time");if(at instanceof Timestamp t)v.setOutboundAt(t.toLocalDateTime());else if(at instanceof java.time.LocalDateTime t)v.setOutboundAt(t);
        var item=one("SELECT * FROM mes_md_item WHERE tenant_id=? AND id=?",tenant(),v.getItemId());if(item!=null){v.setItemCode(code(item.get("code")));v.setItemName(code(item.get("name")));v.setItemSpecification(code(item.get("specification")));v.setUnitMeasureId(id(item.get("unit_measure_id")));}
        if(v.getUnitMeasureId()!=null)v.setUnitMeasureName(name("mes_md_unit_measure",v.getUnitMeasureId()));v.setWarehouseName(name("mes_wm_warehouse",v.getWarehouseId()));v.setLocationName(name("mes_wm_warehouse_location",v.getLocationId()));v.setAreaName(name("mes_wm_warehouse_area",v.getAreaId()));
        v.setOutboundQuantity(scale(b.getOutboundQuantity()));v.setReturnedQuantity(scale(b.getReturnedQuantity()));v.setReservedQuantity(scale(b.getReservedQuantity()));v.setAvailableQuantity(scale(b.getAvailableQuantity()));v.setEditableAvailableQuantity(scale(b.getEditableAvailableQuantity()));v.setSourceReliable(b.isSourceReliable());v.setSourceWarning(b.getSourceWarning());return v;
    }
    private String name(String table,Long id){if(id==null)return null;var row=one("SELECT name FROM "+table+" WHERE tenant_id=? AND id=?",tenant(),id);return row==null?null:code(row.get("name"));}
    @Transactional(readOnly=true)
    public void populate(List<MesWmReturnSalesLineRespVO> values){for(var v:values){v.setSourceReliable(false);v.setAvailableQuantity(scale(BigDecimal.ZERO));v.setEditableAvailableQuantity(scale(BigDecimal.ZERO));if(v.getOriginTransactionId()==null){v.setSourceWarning("历史退货未绑定原出库流水；不得猜配，未执行单请重新选择来源");continue;}try{var parent=returnSalesMapper.selectById(v.getReturnId());if(parent==null)throw invalid("退货父单缺失");var src=origin(parent.getClientId(),v.getOriginTransactionId());Long editing=same(parent.getStatus(),0)?v.getId():null;var b=balance(src,editing,null,false);v.setOriginSalesId(id(src.get("biz_id")));v.setOriginSalesLineId(id(src.get("biz_line_id")));v.setOriginSalesCode(code(src.get("sales_code")));v.setOutboundQuantity(scale(b.getOutboundQuantity()));v.setReturnedQuantity(scale(b.getReturnedQuantity()));v.setReservedQuantity(scale(b.getReservedQuantity()));v.setAvailableQuantity(scale(b.getAvailableQuantity()));v.setEditableAvailableQuantity(scale(b.getEditableAvailableQuantity()));v.setSourceReliable(b.isSourceReliable());v.setSourceWarning(b.getSourceWarning());}catch(ServiceException ex){v.setSourceWarning(ex.getMessage());}}}
}
