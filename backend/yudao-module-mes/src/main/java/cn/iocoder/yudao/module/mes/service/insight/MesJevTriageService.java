package cn.iocoder.yudao.module.mes.service.insight;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantSecretStore;
import cn.iocoder.yudao.module.mes.service.assistant.MesAssistantModelGateway.Purpose;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.service.insight.MesInsightWorkflow.*;

@Service
public class MesJevTriageService {
    private final JdbcTemplate jdbc;
    private final MesAssistantSecretStore secrets;
    private final MesJevClient client;
    @org.springframework.beans.factory.annotation.Autowired private cn.iocoder.yudao.module.mes.service.assistant.MesJevProxySettings proxySettings;
    public MesJevTriageService(JdbcTemplate jdbc,MesAssistantSecretStore secrets,MesJevClient client) { this.jdbc=jdbc;this.secrets=secrets;this.client=client; }
    public record Settings(String model,String apiKey,boolean clearApiKey,boolean enabled) {
        @Override public String toString(){return "JevSettings[model="+model+",apiKey=<redacted>]";}
    }
    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private long user(){Long id=getLoginUserId();if(id==null)throw invalid("请先登录");return id;}
    private Map<String,Object> config(){return jdbc.queryForList("SELECT * FROM mes_insight_jev_config WHERE tenant_id=?",tenant()).stream().findFirst().orElse(Map.of());}
    private boolean configured(Map<String,Object> c){return Boolean.TRUE.equals(c.get("enabled")) && c.get("secret_ciphertext")!=null && !c.get("secret_ciphertext").toString().isBlank();}
    public Map<String,Object> settings(){
        var c=config();return map("endpoint",MesJevClient.ENDPOINT,"model",c.getOrDefault("model_name","jev-1.13.0"),
            "enabled",Boolean.TRUE.equals(c.get("enabled")),"apiKeyConfigured",c.get("secret_ciphertext")!=null,"configured",configured(c),"transport",proxySettings==null?"直连":proxySettings.describe());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(Settings req){
        long actor=user();
        if(req==null || req.model()==null || !Set.of("jev-1.13.0","jev-1.14.0","jev-latest","jev-preview").contains(req.model())) throw invalid("请选择受支持的Jev模型，建议固定版本进行试点");
        String key=req.apiKey()==null?"":req.apiKey().strip();
        if(key.length()>4096 || !key.matches("[\\x21-\\x7E]*") || req.clearApiKey()&&!key.isEmpty()) throw invalid("Jev API Key格式无效；清除与输入新密钥不能同时进行");
        Object encrypted=req.clearApiKey()?null:config().get("secret_ciphertext");
        if(!key.isEmpty()) encrypted=secrets.encrypt(key,tenant());
        if(req.enabled()&&encrypted==null) throw invalid("启用Jev前请填写独立的TypeSafe API Key，不能使用千问密钥");
        jdbc.update("INSERT INTO mes_insight_jev_config(tenant_id,model_name,secret_ciphertext,enabled,updater) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE model_name=VALUES(model_name),secret_ciphertext=VALUES(secret_ciphertext),enabled=VALUES(enabled),updater=VALUES(updater)",tenant(),req.model(),encrypted,req.enabled(),actor);
        return settings();
    }
    public List<Map<String,Object>> departments(){return jdbc.queryForList("SELECT id,name FROM system_dept WHERE tenant_id=? AND deleted=0 AND status=0 ORDER BY sort,id LIMIT 254",tenant());}
    public Map<String,Object> status(){
        var c=config();
        var stats=jdbc.queryForMap("SELECT COUNT(*) AS `generated`,COALESCE(SUM(incident_id IS NOT NULL),0) AS reviewed,COALESCE(SUM(incident_id IS NOT NULL AND category=final_category),0) AS categoryAgreed,COALESCE(SUM(incident_id IS NOT NULL AND department_id IS NOT NULL AND department_id=final_department_id),0) AS departmentAgreed FROM mes_insight_jev_triage WHERE tenant_id=? AND creator=?",tenant(),user());
        return map("configured",configured(c),"model",c.getOrDefault("model_name","jev-1.13.0"),"departments",departments(),"stats",stats);
    }
    private MesJevClient.Decision evaluate(Purpose purpose,String title,String description,Map<String,String> departments){
        var c=config();if(!configured(c)) throw invalid("Jev尚未配置或启用，请管理员填写TypeSafe密钥；仍可手动建立异常");
        return client.evaluate(purpose,c.get("model_name").toString(),title,description,departments);
    }
    public Map<String,Object> test(){
        var result=evaluate(Purpose.JEV_CONNECTION_TEST,"连接测试：产品尺寸检验不合格","仅用于测试，不创建业务记录。",Map.of("D_1","质量部门"));
        return map("connected",true,"model",result.model(),"message","Jev真实接口已返回有效决策；未创建异常或试点评估记录");
    }
    public Map<String,Object> suggest(String title,String description){
        long actor=user();var departmentOptions=new LinkedHashMap<String,String>();
        var deps=departments();for(var dept:deps) departmentOptions.put("D_"+dept.get("id"),dept.get("name").toString());
        var decision=evaluate(Purpose.JEV_TRIAGE,title.strip(),text(description),departmentOptions);
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO mes_insight_jev_triage(tenant_id,creator,input_hash,model_name,category,department_id,category_confidence,department_confidence) VALUES(?,?,?,?,?,?,?,?)",new String[]{"id"});
            Object[] values={tenant(),actor,inputHash(title,description),decision.model(),decision.category(),decision.departmentId(),decision.categoryConfidence(),decision.departmentConfidence()};
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},key);
        if(key.getKey()==null)throw invalid("Jev建议记录保存失败，请重试");
        String departmentName=deps.stream().filter(d->Objects.equals(((Number)d.get("id")).longValue(),decision.departmentId())).map(d->d.get("name").toString()).findFirst().orElse("待人工分派");
        return map("id",key.getKey().longValue(),"model",decision.model(),"category",decision.category(),"departmentId",decision.departmentId(),"departmentName",departmentName,
            "categoryConfidence",decision.categoryConfidence(),"departmentConfidence",decision.departmentConfidence(),"message","建议需人工确认。置信度表示模型分布集中程度，不等于正确率；修改标题或描述后需重新分析。");
    }
    private static String text(String value){return value==null?"":value.strip();}
    public static String inputHash(String title,String description){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text(title)+"\u0000"+text(description)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
