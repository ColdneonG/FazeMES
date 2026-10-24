package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.insight.MesInsightController.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.*;

/** Short claim/finalize transactions never enclose business queries or model HTTP. */
@Service
public class MesInsightConversationStore {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecurityFrameworkService security;
    private final TransactionTemplate tx;
    private final MesInsightInventoryQuery inventory;
    public MesInsightConversationStore(JdbcTemplate jdbc, ObjectMapper json, SecurityFrameworkService security,
                                       PlatformTransactionManager manager, MesInsightInventoryQuery inventory) {
        this.jdbc=jdbc; this.json=json; this.security=security; this.inventory=inventory;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Claim(long id,int version,String token,Map<String,Object> context,Map<String,Object> pending,
                        Map<String,Object> selectedSlots,Set<String> permissions) {}
    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private long user(){Long id=getLoginUserId();if(id==null)throw invalid("请先登录");return id;}
    void base(){require(Set.of("mes:insight:query","mes:insight:chat"));}
    void require(Collection<String> permissions){for(String permission:permissions)if(!security.hasPermission(permission))throw invalid("业务会话所需权限已变更，请重新授权后访问");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw invalid("业务会话保存失败");}}
    private Map<String,Object> decode(Object value){try{return value==null?new LinkedHashMap<>():json.readValue(value.toString(),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ex){throw invalid("业务会话资料损坏，请联系管理员核对");}}
    private Set<String> permissions(Object value){try{return value==null?new LinkedHashSet<>():new LinkedHashSet<>(json.readValue(value.toString(),new TypeReference<List<String>>(){}));}catch(Exception ex){throw invalid("业务会话权限资料损坏");}}
    private long insert(String sql,Object... args){var key=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);if(key.getKey()==null)throw invalid("业务会话保存失败");return key.getKey().longValue();}
    private Map<String,Object> owned(long id,boolean lock){base();var rows=jdbc.queryForList("SELECT * FROM mes_insight_conversation WHERE id=? AND tenant_id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,tenant(),user());if(rows.isEmpty())throw invalid("会话不存在或无权访问");var row=rows.get(0);require(permissions(row.get("permissions_json")));return row;}
    private static int number(Object value){return ((Number)value).intValue();}
    private static long identifier(Object value){return ((Number)value).longValue();}
    private static LocalDateTime local(Object value){return value instanceof Timestamp t?t.toLocalDateTime():value instanceof LocalDateTime t?t:null;}
    static String iso(Object value){LocalDateTime t=local(value);return t==null?null:t.atZone(ZONE).toOffsetDateTime().toString();}
    private Map<String,Object> summary(Map<String,Object> row){return map("id",row.get("id"),"title",row.get("title"),"version",row.get("version"),"turnCount",row.get("turn_count"),"createdAt",iso(row.get("create_time")),"updatedAt",iso(row.get("update_time")));}
    private long createInternal(String title){base();if(title==null||title.isBlank())title="新业务会话";title=title.strip();if(title.length()>160)throw invalid("会话标题最多160字");Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mes_insight_conversation WHERE tenant_id=? AND user_id=?",Integer.class,tenant(),user());if(count!=null&&count>=200)throw invalid("业务会话已达到200个，请先删除旧会话");LocalDateTime now=LocalDateTime.now(ZONE);return insert("INSERT INTO mes_insight_conversation(tenant_id,user_id,title,permissions_json,context_json,create_time,update_time) VALUES(?,?,?,'[]','{}',?,?)",tenant(),user(),title,now,now);}
    public Map<String,Object> create(String title){return tx.execute(status->{long id=createInternal(title);return summary(owned(id,false));});}
    @SuppressWarnings("unchecked")
    public Claim claim(Question req){return tx.execute(status->{
        base();if(req.conversationId()!=null&&req.version()==null)throw invalid("请提供当前会话版本");
        long id=req.conversationId()==null?createInternal(null):req.conversationId();
        var row=owned(id,true);int version=number(row.get("version"));
        if(req.conversationId()!=null&&req.version()!=version)throw invalid("会话版本已变更，请刷新后重试");
        LocalDateTime until=local(row.get("processing_until"));if(row.get("processing_token")!=null&&until!=null&&until.isAfter(LocalDateTime.now(ZONE)))throw invalid("当前会话正在处理问题，请稍后刷新");
        if(number(row.get("turn_count"))>=200)throw invalid("当前会话已达到200轮，请创建新会话");
        Map<String,Object> pending=decode(row.get("pending_json")),selected=new LinkedHashMap<>();
        if(req.clarification()!=null){
            var supplied=req.clarification();
            if(!Objects.equals(supplied.token(),pending.get("token"))||!Objects.equals(numberOrNull(pending.get("version")),version))throw invalid("澄清选项已失效，请刷新会话");
            if(pending.get("expiresAt")==null||!OffsetDateTime.parse(pending.get("expiresAt").toString()).isAfter(OffsetDateTime.now(ZONE)))throw invalid("澄清选项已过期，请重新提问");
            var choices=(List<Map<String,Object>>)pending.getOrDefault("choices",List.of());
            var choice=choices.stream().filter(c->Objects.equals(supplied.choiceId(),c.get("id"))).findFirst().orElseThrow(()->invalid("无效澄清选项"));
            selected.putAll((Map<String,Object>)choice.get("slots"));
        }else if(req.question()==null||req.question().isBlank())throw invalid("请输入问题或选择澄清选项");
        if(req.question()!=null&&req.question().length()>1000)throw invalid("问题最多1000字");
        String token=UUID.randomUUID().toString();jdbc.update("UPDATE mes_insight_conversation SET processing_token=?,processing_until=? WHERE id=? AND tenant_id=? AND user_id=?",token,LocalDateTime.now(ZONE).plusMinutes(15),id,tenant(),user());
        return new Claim(id,version,token,decode(row.get("context_json")),pending,selected,permissions(row.get("permissions_json")));
    });}
    private static Integer numberOrNull(Object value){return value instanceof Number n?n.intValue():null;}
    public void release(Claim claim){tx.executeWithoutResult(status->jdbc.update("UPDATE mes_insight_conversation SET processing_token=NULL,processing_until=NULL WHERE id=? AND tenant_id=? AND user_id=? AND version=? AND processing_token=?",claim.id(),tenant(),user(),claim.version(),claim.token()));}
    @SuppressWarnings("unchecked")
    public Map<String,Object> finish(Claim claim,String question,Map<String,Object> context,Map<String,Object> pending,
                                     Map<String,Object> answer,Collection<String> required){return tx.execute(status->{
        var row=owned(claim.id(),true);if(number(row.get("version"))!=claim.version()||!Objects.equals(row.get("processing_token"),claim.token()))throw invalid("会话版本已变更，请刷新后重试");
        Set<String> accumulated=permissions(row.get("permissions_json"));accumulated.addAll(required);require(accumulated);
        int version=claim.version()+1;var fullEvidence=answer.get("evidence") instanceof Map<?,?> evidence?new LinkedHashMap<>((Map<String,Object>)evidence):new LinkedHashMap<String,Object>();fullEvidence.remove("_requiredPermissions");
        var result=new LinkedHashMap<>(answer);result.put("conversationId",claim.id());result.put("version",version);
        result.put("evidence",pageEvidence(fullEvidence,1,20));
        long turn=insert("INSERT INTO mes_insight_turn(tenant_id,user_id,conversation_id,version,question,answer_json,create_time) VALUES(?,?,?,?,?,'{}',?)",tenant(),user(),claim.id(),version,question,LocalDateTime.now(ZONE));
        result.put("turnId",turn);jdbc.update("UPDATE mes_insight_turn SET answer_json=? WHERE id=? AND tenant_id=? AND user_id=?",encode(result),turn,tenant(),user());
        jdbc.update("INSERT INTO mes_insight_evidence(turn_id,tenant_id,user_id,conversation_id,evidence_json) VALUES(?,?,?,?,?)",turn,tenant(),user(),claim.id(),encode(fullEvidence));
        if(!pending.isEmpty())pending.put("version",version);
        String title=number(row.get("turn_count"))==0&&"新业务会话".equals(row.get("title"))?question.substring(0,Math.min(80,question.length())):row.get("title").toString();
        jdbc.update("UPDATE mes_insight_conversation SET title=?,version=?,turn_count=turn_count+1,context_json=?,pending_json=?,permissions_json=?,processing_token=NULL,processing_until=NULL,update_time=? WHERE id=? AND tenant_id=? AND user_id=? AND version=? AND processing_token=?",title,version,encode(context),encode(pending),encode(accumulated),LocalDateTime.now(ZONE),claim.id(),tenant(),user(),claim.version(),claim.token());
        inventory.refreshSourcePermissions(result);return result;
    });}
    public Map<String,Object> page(int pageNo,int pageSize){base();validatePage(pageNo,pageSize);var all=jdbc.queryForList("SELECT * FROM mes_insight_conversation WHERE tenant_id=? AND user_id=? ORDER BY update_time DESC,id DESC",tenant(),user());var visible=new ArrayList<Map<String,Object>>();for(var row:all){Set<String> needed=permissions(row.get("permissions_json"));if(needed.stream().allMatch(security::hasPermission))visible.add(summary(row));}int from=(int)Math.min((long)(pageNo-1)*pageSize,visible.size());return map("list",visible.subList(from,Math.min(from+pageSize,visible.size())),"total",visible.size());}
    public Map<String,Object> get(long id,Long beforeTurnId,int pageSize){validatePage(1,pageSize);return tx.execute(status->{var conversation=owned(id,false);String before=beforeTurnId==null?"":" AND id<?";var args=new ArrayList<Object>(List.of(tenant(),user(),id));if(beforeTurnId!=null)args.add(beforeTurnId);args.add(pageSize+1);var rows=jdbc.queryForList("SELECT id,question,version,create_time,answer_json FROM mes_insight_turn WHERE tenant_id=? AND user_id=? AND conversation_id=?"+before+" ORDER BY id DESC LIMIT ?",args.toArray());boolean more=rows.size()>pageSize;if(more)rows=new ArrayList<>(rows.subList(0,pageSize));Collections.reverse(rows);var turns=new ArrayList<Map<String,Object>>();for(var row:rows){var answer=decode(row.get("answer_json"));inventory.refreshSourcePermissions(answer);turns.add(map("id",row.get("id"),"question",row.get("question"),"version",row.get("version"),"createdAt",iso(row.get("create_time")),"answer",answer));}return map("conversation",summary(conversation),"turns",turns,"hasMore",more,"nextBeforeTurnId",more&&!turns.isEmpty()?turns.get(0).get("id"):null);});}
    public void delete(long id,int version){tx.executeWithoutResult(status->{var row=owned(id,true);if(number(row.get("version"))!=version)throw invalid("会话版本已变更，请刷新后重试");LocalDateTime until=local(row.get("processing_until"));if(row.get("processing_token")!=null&&until!=null&&until.isAfter(LocalDateTime.now(ZONE)))throw invalid("当前会话正在处理问题，请稍后重试");jdbc.update("DELETE FROM mes_insight_evidence WHERE tenant_id=? AND user_id=? AND conversation_id=?",tenant(),user(),id);jdbc.update("DELETE FROM mes_insight_turn WHERE tenant_id=? AND user_id=? AND conversation_id=?",tenant(),user(),id);jdbc.update("DELETE FROM mes_insight_conversation WHERE tenant_id=? AND user_id=? AND id=?",tenant(),user(),id);});}
    public Map<String,Object> artifact(long turnId){return tx.execute(status->{base();var refs=jdbc.queryForList("SELECT conversation_id FROM mes_insight_turn WHERE id=? AND tenant_id=? AND user_id=?",turnId,tenant(),user());if(refs.isEmpty())throw invalid("依据不存在或无权访问");owned(identifier(refs.get(0).get("conversation_id")),false);var rows=jdbc.queryForList("SELECT evidence_json FROM mes_insight_evidence WHERE turn_id=? AND tenant_id=? AND user_id=?",turnId,tenant(),user());if(rows.isEmpty())throw invalid("依据不存在或无权访问");var result=decode(rows.get(0).get("evidence_json"));inventory.refreshSourcePermissions(result);return result;});}
    public Map<String,Object> evidence(long turnId,int pageNo,int pageSize){validatePage(pageNo,pageSize);return pageEvidence(artifact(turnId),pageNo,pageSize);}
    @SuppressWarnings("unchecked") private Map<String,Object> pageEvidence(Map<String,Object> full,int pageNo,int pageSize){var result=new LinkedHashMap<>(full);if(!Set.of("INVENTORY_LEDGER","STOCK_DOCUMENT","AGENT_TABLE").contains(Objects.toString(result.get("kind"),"")))return result;var rows=(List<Object>)full.getOrDefault("list",List.of());int from=(int)Math.min((long)(pageNo-1)*pageSize,rows.size());result.put("list",new ArrayList<>(rows.subList(from,Math.min(from+pageSize,rows.size()))));result.put("total",rows.size());result.put("pageNo",pageNo);result.put("pageSize",pageSize);result.put("hasMore",from+pageSize<rows.size());return result;}
    private void validatePage(int pageNo,int pageSize){if(pageNo<1||pageSize<1||pageSize>100)throw invalid("分页参数无效");}
}
