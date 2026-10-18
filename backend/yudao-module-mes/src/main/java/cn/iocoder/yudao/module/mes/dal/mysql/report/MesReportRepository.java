package cn.iocoder.yudao.module.mes.dal.mysql.report;

import cn.iocoder.yudao.module.mes.dal.dataobject.report.MesReportDefinitionDO;
import cn.iocoder.yudao.module.mes.service.report.MesReportDatasetRegistry.Query;
import cn.iocoder.yudao.module.mes.service.report.MesReportTimeFormat;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.module.mes.service.report.MesReportErrorConstants.*;

/** JDBC bypasses MyBatis tenant interception: tenant and deleted predicates are mandatory here. */
@Repository
public class MesReportRepository {
    @Resource private JdbcTemplate jdbc;
    public record Version(String name,String config,LocalDateTime createTime) {}
    public List<MesReportDefinitionDO> list(long tenant,boolean draftReader) {
        String selected=draftReader?"d.latest_version":"d.published_version";
        String updated=draftReader?"d.update_time":"v.create_time";
        return jdbc.query("SELECT d.id,v.name,"+selected+" AS latest_version,d.published_version,"+updated+" AS update_time FROM mes_report_definition d JOIN mes_report_version v ON v.report_id=d.id AND v.tenant_id=d.tenant_id AND v.version="+selected+" AND v.deleted=0 WHERE d.tenant_id=? AND d.deleted=0 ORDER BY "+updated+" DESC,d.id DESC LIMIT 200",(r,n)->new MesReportDefinitionDO().setId(r.getLong("id")).setName(r.getString("name")).setLatestVersion(r.getInt("latest_version")).setPublishedVersion((Integer)r.getObject("published_version")).setTenantId(tenant).setUpdateTime(r.getTimestamp("update_time").toLocalDateTime()),tenant);
    }
    public MesReportDefinitionDO get(long id,long tenant,boolean lock) {
        List<MesReportDefinitionDO> rows=jdbc.query("SELECT * FROM mes_report_definition WHERE id=? AND tenant_id=? AND deleted=0"+(lock?" FOR UPDATE":""),(r,n)->new MesReportDefinitionDO().setId(id).setLatestVersion(r.getInt("latest_version")).setPublishedVersion((Integer)r.getObject("published_version")).setTenantId(tenant).setUpdateTime(r.getTimestamp("update_time").toLocalDateTime()),id,tenant);
        if(rows.isEmpty()) throw missing();return rows.get(0);
    }
    public long create(long tenant,long user) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var p=connection.prepareStatement("INSERT INTO mes_report_definition(latest_version,tenant_id,creator,updater) VALUES(0,?,?,?)",new String[]{"id"});p.setLong(1,tenant);p.setString(2,Long.toString(user));p.setString(3,Long.toString(user));return p;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void save(long id,String name,int version,String json,long tenant,long user) {
        jdbc.update("INSERT INTO mes_report_version(report_id,version,name,config_json,tenant_id,creator) VALUES(?,?,?,?,?,?)",id,version,name,json,tenant,Long.toString(user));
        jdbc.update("UPDATE mes_report_definition SET latest_version=?,updater=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND deleted=0",version,Long.toString(user),id,tenant);
    }
    public String config(long id,int version,long tenant) {
        return version(id,version,tenant).config();
    }
    public Version version(long id,int version,long tenant) {
        List<Version> rows=jdbc.query("SELECT name,config_json,create_time FROM mes_report_version WHERE report_id=? AND version=? AND tenant_id=? AND deleted=0",(r,n)->new Version(r.getString(1),r.getString(2),r.getTimestamp(3).toLocalDateTime()),id,version,tenant);
        if(rows.isEmpty()) throw missing();return rows.get(0);
    }
    public List<Map<String,Object>> versions(long id,long tenant) {return jdbc.query("SELECT version,create_time,name FROM mes_report_version WHERE report_id=? AND tenant_id=? AND deleted=0 ORDER BY version DESC LIMIT 100",(r,n)->Map.of("version",r.getInt(1),"createTime",MesReportTimeFormat.isoValue(r.getTimestamp(2)),"name",r.getString(3)),id,tenant);}
    public void publish(long id,int version,long tenant,long user) {jdbc.update("UPDATE mes_report_definition SET published_version=?,updater=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND deleted=0",version,Long.toString(user),id,tenant);}
    public List<Map<String,Object>> query(Query q) {return jdbc.query(q.sql(),(r,n)->{Map<String,Object> row=new LinkedHashMap<>();for(var col:q.columns()){String k=(String)col.get("key");row.put(k,MesReportTimeFormat.isoValue(r.getObject(k)));}return row;},q.parameters().toArray());}
}
