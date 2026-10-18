package cn.iocoder.yudao.module.mes.service.face;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/** One-to-one verification only. The claimed username is never inferred from a face. */
@Service
public class MesFaceAuthService {
    static final ErrorCode UNAVAILABLE=new ErrorCode(1_047_140_000,"本地人脸核验服务暂不可用");
    static final ErrorCode REJECTED=new ErrorCode(1_047_140_001,"人脸核验未通过，请使用密码登录或检查本人录入");
    static final ErrorCode INVALID=new ErrorCode(1_047_140_002,"请提供一张清晰正脸，并完成本人授权");
    static final ErrorCode RATE_LIMITED=new ErrorCode(1_047_140_003,"人脸核验过于频繁，请稍后重试");
    @Resource private JdbcTemplate jdbc;
    @Resource private AdminUserService users;
    @Resource private AdminAuthService auth;
    @Resource private FaceWorkerClient worker;
    @Resource private FaceTemplateCipher cipher;
    @Resource private FaceChallengeStore challenges;
    private long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private String username(String value){
        if(value==null||value.isBlank()||value.length()>64)throw exception(INVALID);
        return value.trim();
    }
    private AdminUserDO current(String password){
        Long id=getLoginUserId();
        if(id==null)throw exception(REJECTED);
        AdminUserDO user=users.getUser(id);
        if(user==null||!Objects.equals(user.getTenantId(),tenant()))throw exception(REJECTED);
        try{challenges.limit("enroll:"+tenant()+":"+id,300,5);}
        catch(IllegalArgumentException ex){throw exception(RATE_LIMITED);}
        AdminUserDO verified=auth.authenticate(user.getUsername(),password);
        if(verified==null||!Objects.equals(verified.getId(),id))throw exception(REJECTED);
        return user;
    }
    public Map<String,Object> status(){
        long user=Objects.requireNonNull(getLoginUserId());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_credential WHERE tenant_id=? AND user_id=? AND enabled=1 AND deleted=0",Long.class,tenant(),user);
        return Map.of("enrolled",count!=null&&count>0,"ready",worker.ready(),"model","SFace+YuNet","comparison","1:1","livenessSupported",false);
    }
    public Map<String,Object> publicStatus(){
        return Map.of("ready",worker.ready(),"comparison","1:1","livenessSupported",false);
    }
    public Map<String,Object> challenge(String username,String ip){
        try{return Map.of("challenge",challenges.issue(tenant(),username(username),ip),"expiresIn",60);}
        catch(IllegalArgumentException ex){throw exception(RATE_LIMITED);}
    }
    @Transactional
    public void enroll(String password,boolean consent,List<String> images){
        if(!consent||images==null||images.isEmpty()||images.size()>3)throw exception(INVALID);
        AdminUserDO user=current(password);
        List<float[]> vectors=new ArrayList<>();
        try {
            for(String image:images)vectors.add(worker.extract(image));
            for(int i=1;i<vectors.size();i++)if(similarity(vectors.get(0),vectors.get(i))<0.54)throw exception(INVALID);
            String sealed=cipher.encrypt(tenant(),user.getId(),vectors);
            jdbc.update("INSERT INTO mes_face_credential(tenant_id,user_id,template_cipher,enabled,creator,updater) VALUES(?,?,?,1,?,?) "
                    +"ON DUPLICATE KEY UPDATE template_cipher=VALUES(template_cipher),enabled=1,deleted=0,updater=VALUES(updater),update_time=CURRENT_TIMESTAMP",
                    tenant(),user.getId(),sealed,user.getId().toString(),user.getId().toString());
        }catch(IllegalArgumentException ex){throw exception(INVALID);}
        catch(IllegalStateException ex){throw exception(UNAVAILABLE);}
    }
    @Transactional
    public void disable(String password){
        AdminUserDO user=current(password);
        jdbc.update("UPDATE mes_face_credential SET template_cipher='',enabled=0,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND user_id=? AND deleted=0",
                user.getId().toString(),tenant(),user.getId());
    }
    public AuthLoginRespVO login(String username,String challenge,String image,String ip){
        String name=username(username);
        try{if(!challenges.consume(tenant(),name,ip,challenge))throw exception(REJECTED);}
        catch(IllegalArgumentException ex){throw exception(RATE_LIMITED);}
        AdminUserDO user=users.getUserByUsername(name);
        if(user==null||!Objects.equals(user.getTenantId(),tenant())||!Objects.equals(user.getStatus(),0))throw exception(REJECTED);
        List<String> stored=jdbc.query("SELECT template_cipher FROM mes_face_credential WHERE tenant_id=? AND user_id=? AND enabled=1 AND deleted=0",
                (r,n)->r.getString(1),tenant(),user.getId());
        if(stored.size()!=1)throw exception(REJECTED);
        try{
            float[] probe=worker.extract(image);
            double match=cipher.decrypt(tenant(),user.getId(),stored.get(0)).stream().mapToDouble(reference->similarity(probe,reference)).max().orElse(-1);
            // Match the reference project's maximum cosine distance of 0.46.
            if(match<0.54)throw exception(REJECTED);
        }catch(IllegalArgumentException ex){throw exception(REJECTED);}
        catch(IllegalStateException ex){throw exception(UNAVAILABLE);}
        return auth.loginByVerifiedFace(user.getId());
    }
    static double similarity(float[] left,float[] right){
        FaceTemplateCipher.validate(left);FaceTemplateCipher.validate(right);
        double result=0;for(int i=0;i<128;i++)result+=left[i]*right[i];return result;
    }
}
