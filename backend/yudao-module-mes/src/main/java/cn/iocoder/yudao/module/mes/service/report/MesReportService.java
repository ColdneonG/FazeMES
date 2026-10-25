package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.report.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.report.MesReportDefinitionDO;
import cn.iocoder.yudao.module.mes.dal.mysql.report.MesReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.report.MesReportErrorConstants.*;

@Service
public class MesReportService {
    @Resource private MesReportRepository repository;
    @Resource private MesReportDatasetRegistry registry;
    @Resource private ObjectMapper objectMapper;
    public List<MesReportDefinitionDO> list(){return list(false);}
    public List<MesReportDefinitionDO> list(boolean draftReader){return repository.list(TenantContextHolder.getRequiredTenantId(),draftReader);}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(MesReportSaveReqVO req,long user) {
        long tenant=TenantContextHolder.getRequiredTenantId();registry.compile(req.getConfig(),tenant,1,1);
        if(req.getName()==null||req.getName().isBlank()||req.getName().length()>100)throw invalid("报表名称需为1至100个字符");
        long id;int version;
        if(req.getId()==null){id=repository.create(tenant,user);version=1;}
        else {id=req.getId();var current=repository.get(id,tenant,true);if(!Objects.equals(current.getLatestVersion(),req.getExpectedVersion()))throw conflict();version=current.getLatestVersion()+1;}
        try{repository.save(id,req.getName(),version,objectMapper.writeValueAsString(req.getConfig()),tenant,user);}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw invalid("报表配置无法保存");}
        return Map.of("id",id,"version",version);
    }
    public Map<String,Object> get(long id,Integer version,boolean designer) {
        long tenant=TenantContextHolder.getRequiredTenantId();var d=repository.get(id,tenant,false);
        int selected=resolve(d,version,designer);Map<String,Object> result=new LinkedHashMap<>();
        var snapshot=repository.version(id,selected,tenant);d.setName(snapshot.name());
        if(!designer)d.setLatestVersion(selected).setUpdateTime(snapshot.createTime());
        result.put("definition",d);result.put("version",selected);result.put("config",read(snapshot.config()));
        result.put("versions",designer?repository.versions(id,tenant):List.of(Map.of("version",selected)));return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public void publish(long id,int version,long user) {
        long tenant=TenantContextHolder.getRequiredTenantId();var d=repository.get(id,tenant,true);
        if(version!=d.getLatestVersion())throw conflict();registry.compile(read(repository.config(id,version,tenant)),tenant,1,1);
        repository.publish(id,version,tenant,user);
    }
    private int resolve(MesReportDefinitionDO d,Integer version,boolean designer) {
        Integer selected=version!=null?version:(designer?d.getLatestVersion():d.getPublishedVersion());
        if(selected==null||(!designer&&!Objects.equals(selected,d.getPublishedVersion())))throw missing();return selected;
    }
    public MesReportConfig config(MesReportPreviewReqVO req,boolean designer,boolean draftReader) {
        long tenant=TenantContextHolder.getRequiredTenantId();
        if(req.getConfig()!=null){if(!designer)throw invalid("只有设计权限可以预览未保存配置");return req.getConfig();}
        if(req.getId()==null)throw invalid("请选择报表");var d=repository.get(req.getId(),tenant,false);
        return read(repository.config(req.getId(),resolve(d,req.getVersion(),draftReader),tenant));
    }
    public Map<String,Object> preview(MesReportPreviewReqVO req,boolean designer,boolean export,boolean draftReader) {
        int size=export?5000:(req.getPageSize()==null?50:req.getPageSize());
        if(!export&&(size<1||size>200))throw invalid("每页最多200行");
        int page=export?1:(req.getPageNo()==null?1:req.getPageNo());
        var reportConfig=config(req,designer,draftReader);
        var q=registry.compile(reportConfig,TenantContextHolder.getRequiredTenantId(),page,size);
        var rows=new ArrayList<>(repository.query(q));boolean more=rows.size()>size;if(more)rows.remove(rows.size()-1);
        if(!export) {
            // SQL uses c0/c1 aliases, so generic JSON field-name advice cannot
            // identify their quantity semantics. Resolve them from the trusted
            // column definitions; COUNT remains an integer, not a quantity.
            Set<String> quantityAliases=new HashSet<>();
            for(var column:q.columns()) {
                String field=(String)column.get("field");
                boolean quantity="quantity".equals(field)||field.endsWith("_quantity")||field.startsWith("quantity_");
                boolean count=reportConfig.getColumns().stream().anyMatch(c->field.equals(c.getField())&&"COUNT".equals(c.getAggregate()));
                if(quantity&&!count) quantityAliases.add((String)column.get("key"));
            }
            rows.replaceAll(row->{
                Map<String,Object> exact=new LinkedHashMap<>(row);
                for(String alias:quantityAliases) {
                    if(exact.get(alias) instanceof BigDecimal value) exact.put(alias,value.toPlainString());
                }
                return exact;
            });
        }
        return Map.of("columns",q.columns(),"rows",rows,"hasMore",more,"pageNo",page,"pageSize",size,"generatedAt",MesReportTimeFormat.isoValue(java.time.LocalDateTime.now()));
    }
    private MesReportConfig read(String json) {try{return objectMapper.readValue(json,MesReportConfig.class);}catch(Exception ex){throw invalid("已保存的报表配置无法读取");}}
}
