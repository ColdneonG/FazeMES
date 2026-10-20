package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Only short persistence transactions. No retrieval, model call or provider bytes run inside these callbacks. */
@Service
public class MesAssistantMessageStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public MesAssistantMessageStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.json=json;transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Claim(long tenantId,long userId,long sessionId,int version,String requestId,long userMessageId,List<Map<String,Object>> history) { }

    public Claim claim(long sessionId,long userId,String question){
        long tenant=TenantContextHolder.getRequiredTenantId();
        return transaction.execute(status->{
            var session=required(sessionId,userId,tenant);
            if(session.get("pending_request_id")!=null)
                throw MesAssistantErrors.invalid("此会话正在处理请求或上次请求尚未完成，请稍后再试；长时间未完成可删除后新建会话");
            Integer version=((Number)session.get("version")).intValue();
            if(version>=Integer.MAX_VALUE-1)throw MesAssistantErrors.invalid("会话版本已达到上限，请新建会话");
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM mes_assistant_message WHERE session_id=? AND tenant_id=? AND user_id=? AND deleted=0",Long.class,sessionId,tenant,userId);
            if(count!=null&&count>=198)throw MesAssistantErrors.invalid("会话已达到200条，请新建会话");
            String request=UUID.randomUUID().toString();
            jdbc.update("UPDATE mes_assistant_session SET version=?,pending_request_id=?,pending_started_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND user_id=? AND deleted=0",version+1,request,sessionId,tenant,userId);
            long userMessage=insert("INSERT INTO mes_assistant_message(session_id,user_id,role,content_text,status,citations_json,tenant_id) VALUES(?,?,'user',?,'PROCESSING','[]',?)",sessionId,userId,question,tenant);
            var history=jdbc.query("SELECT role,content_text AS content FROM mes_assistant_message WHERE session_id=? AND tenant_id=? AND user_id=? AND deleted=0 AND status='ANSWERED' ORDER BY id DESC LIMIT 12",(rs,index)->{
                String role=rs.getString("role"),content=rs.getString("content");
                if(role==null||content==null)throw MesAssistantErrors.invalid("会话历史内容不完整，请核对后重试");
                return Map.<String,Object>of("role",role,"content",content);
            },sessionId,tenant,userId);
            Collections.reverse(history);
            return new Claim(tenant,userId,sessionId,version+1,request,userMessage,List.copyOf(history));
        });
    }

    public Map<String,Object> finish(Claim claim,String question,String resultStatus,String answer,List<Map<String,Object>> citations,Object model){
        String encoded=encode(citations);
        return transaction.execute(status->{
            var session=required(claim.sessionId(),claim.userId(),claim.tenantId());
            if(!Objects.equals(session.get("pending_request_id"),claim.requestId())||((Number)session.get("version")).intValue()!=claim.version())
                throw MesAssistantErrors.invalid("会话已变更，本次答复未保存，请重新打开会话");
            if(claim.version()==Integer.MAX_VALUE)throw MesAssistantErrors.invalid("会话版本已达到上限，请新建会话");
            int messageUpdated=jdbc.update("UPDATE mes_assistant_message SET status=?,citations_json=?,model_name=? WHERE id=? AND session_id=? AND tenant_id=? AND user_id=? AND role='user' AND status='PROCESSING' AND deleted=0",resultStatus,encoded,model,claim.userMessageId(),claim.sessionId(),claim.tenantId(),claim.userId());
            if(messageUpdated!=1)throw MesAssistantErrors.invalid("会话请求已变更，本次答复未保存，请重新打开会话");
            Long assistantMessage=answer==null?null:insert("INSERT INTO mes_assistant_message(session_id,user_id,role,content_text,status,citations_json,model_name,tenant_id) VALUES(?,?,'assistant',?,'ANSWERED',?,?,?)",claim.sessionId(),claim.userId(),answer,encoded,model,claim.tenantId());
            String title=Objects.toString(session.get("title"),"新会话");
            if(title.equals("新会话"))title=question.substring(0,Math.min(40,question.length()));
            int updated=jdbc.update("UPDATE mes_assistant_session SET title=?,version=version+1,pending_request_id=NULL,pending_started_at=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND user_id=? AND deleted=0 AND version=? AND pending_request_id=?",title,claim.sessionId(),claim.tenantId(),claim.userId(),claim.version(),claim.requestId());
            if(updated!=1)throw MesAssistantErrors.invalid("会话已变更，本次答复未保存，请重新打开会话");
            var result=new LinkedHashMap<String,Object>();result.put("userMessageId",claim.userMessageId());result.put("assistantMessageId",assistantMessage);return result;
        });
    }
    public void release(Claim claim,List<Map<String,Object>> citations,Object model){
        if(claim.tenantId()!=TenantContextHolder.getRequiredTenantId())throw MesAssistantErrors.missing();
        String encoded=encode(citations);
        transaction.executeWithoutResult(status->{
            var live=jdbc.queryForList("SELECT id FROM mes_assistant_session WHERE id=? AND tenant_id=? AND user_id=? AND deleted=0 FOR UPDATE",claim.sessionId(),claim.tenantId(),claim.userId());
            if(live.isEmpty())return; // A deleted session and its messages must never be revived.
            jdbc.update("UPDATE mes_assistant_message SET status='MODEL_ERROR',citations_json=?,model_name=? WHERE id=? AND session_id=? AND tenant_id=? AND user_id=? AND role='user' AND status='PROCESSING' AND deleted=0",encoded,model,claim.userMessageId(),claim.sessionId(),claim.tenantId(),claim.userId());
            jdbc.update("UPDATE mes_assistant_session SET pending_request_id=NULL,pending_started_at=NULL,version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND user_id=? AND deleted=0 AND version=? AND pending_request_id=?",claim.sessionId(),claim.tenantId(),claim.userId(),claim.version(),claim.requestId());
        });
    }
    private Map<String,Object> required(long session,long user,long tenant){
        if(user<=0||tenant!=TenantContextHolder.getRequiredTenantId())throw MesAssistantErrors.missing();
        var records=jdbc.queryForList("SELECT id,title,version,pending_request_id FROM mes_assistant_session WHERE id=? AND tenant_id=? AND user_id=? AND deleted=0 FOR UPDATE",session,tenant,user);
        if(records.size()!=1)throw MesAssistantErrors.missing();return records.get(0);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Knowledge citation serialization failed");}}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return Objects.requireNonNull(key.getKey()).longValue();}
}
