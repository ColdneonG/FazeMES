package cn.iocoder.yudao.module.mes.service.wm.barcode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.MesWmBarcodeSaveReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.wm.barcode.vo.config.MesWmBarcodeConfigSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.barcode.MesWmBarcodeDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.barcode.MesWmBarcodeConfigDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.barcode.MesWmBarcodeMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.barcode.MesWmBarcodeConfigMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * Actual services, canonical resolver, current-read guards and global/tenant mutex on native InnoDB.
 * Only mapper mutations are adapters; they persist real rows using the service transaction connection.
 */
@EnabledIfSystemProperty(named="mes.barcode.mysql.url",
        matches="jdbc:mysql://127\\.0\\.0\\.1:59685/mes_barcode_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmBarcodeMySqlTest {
    private static final List<String> TABLES=List.of("mes_wm_barcode","mes_wm_barcode_config","mes_wm_barcode_mutex","mes_md_item");
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private DataSourceTransactionManager manager;
    private MesWmBarcodeService barcodes;
    private MesWmBarcodeConfigService configs;
    private MutationAdapter mutations;
    private String schema;
    private boolean ownsTables;

    @BeforeEach void setup() {
        String url=System.getProperty("mes.barcode.mysql.url"); URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost()); assertEquals(59685,uri.getPort());
        schema=uri.getPath().substring(1); assertTrue(schema.matches("mes_barcode_it_[A-Za-z0-9_]+"));
        source=new DriverManagerDataSource(url,System.getProperty("mes.barcode.mysql.user","root"),System.getProperty("mes.barcode.mysql.password",""));
        jdbc=new JdbcTemplate(source); verifyOwnership();
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",schema).isEmpty(),
                "Use an explicitly EMPTY disposable schema; never use an application database");
        ownsTables=true;
        String audit=",tenant_id BIGINT NOT NULL,deleted BIT NOT NULL DEFAULT 0,creator VARCHAR(64) DEFAULT '',updater VARCHAR(64) DEFAULT '',create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP";
        jdbc.execute("CREATE TABLE mes_wm_barcode_mutex(tenant_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO mes_wm_barcode_mutex VALUES(-1)");
        jdbc.execute("CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT"+audit+") ENGINE=InnoDB");
        // Match the maintained legacy schema. In particular, do not give this fixture
        // identity/content indexes that the real upgrade deliberately does not add.
        jdbc.execute("CREATE TABLE mes_wm_barcode_config(id BIGINT AUTO_INCREMENT PRIMARY KEY,format INT,biz_type INT,content_format VARCHAR(255),content_example VARCHAR(255),auto_generate_flag BIT,default_template VARCHAR(255),status INT,remark VARCHAR(255)"+audit+",KEY idx_tenant_deleted(tenant_id,deleted)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_barcode(id BIGINT AUTO_INCREMENT PRIMARY KEY,config_id BIGINT,format INT,biz_type INT,content TEXT,biz_id BIGINT,biz_code VARCHAR(128),biz_name VARCHAR(255),status INT,remark VARCHAR(255)"+audit+",KEY idx_config_id(config_id),KEY idx_biz_id(biz_id),KEY idx_tenant_deleted(tenant_id,deleted)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO mes_md_item(id,code,name,status,tenant_id) VALUES(1,'ITEM-1','物料1',0,1),(2,'ITEM-2','物料2',0,1),(101,'ITEM-101','物料101',0,2),(102,'ITEM-102','物料102',0,2)");
        jdbc.update("INSERT INTO mes_wm_barcode_config(id,format,biz_type,content_format,auto_generate_flag,default_template,status,tenant_id) VALUES(1,1,600,'{BUSINESSCODE}',1,'LABEL_50X30',0,1),(2,1,600,'{BUSINESSCODE}',1,'LABEL_50X30',0,2)");
        context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->source); context.register(Configuration.class); context.refresh();
        manager=context.getBean(DataSourceTransactionManager.class); barcodes=context.getBean(MesWmBarcodeService.class);
        configs=context.getBean(MesWmBarcodeConfigService.class); mutations=context.getBean(MutationAdapter.class);
        assertTrue(AopUtils.isAopProxy(barcodes)); assertTrue(AopUtils.isAopProxy(configs));
        TenantContextHolder.setTenantId(1L);
    }
    private void verifyOwnership() {
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(59685,jdbc.queryForObject("SELECT @@port",Integer.class));
    }
    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        if(context!=null)context.close();
        if(ownsTables) { verifyOwnership(); for(String table:TABLES)jdbc.execute("DROP TABLE IF EXISTS "+table); }
    }
    private TransactionTemplate transaction() {
        var tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); return tx;
    }
    private MesWmBarcodeSaveReqVO request(long id,String content) {
        return new MesWmBarcodeSaveReqVO().setBizType(600).setBizId(id).setBizCode("CALLER-SPOOF").setBizName("CALLER-NAME").setContent(content).setStatus(0);
    }
    private MesWmBarcodeConfigSaveReqVO config(Long id,int type,int status) {
        return new MesWmBarcodeConfigSaveReqVO().setId(id).setFormat(1).setBizType(type).setContentFormat("{BUSINESSCODE}")
                .setAutoGenerateFlag(true).setDefaultTemplate("LABEL_50X30").setStatus(status);
    }
    private record Outcome(Long id,RuntimeException failure,long connectionId) { }
    private List<Outcome> race(long firstTenant,long secondTenant,Supplier<Long> first,Supplier<Long> second,boolean configuration) throws Exception {
        var executor=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        var atInsert=new CountDownLatch(1); var releaseInsert=new CountDownLatch(1); var firstInsert=new AtomicBoolean();
        var connections=new ConcurrentHashMap<Long,Boolean>();
        Runnable hold=()-> {
            if(firstInsert.compareAndSet(false,true)) {
                atInsert.countDown();
                try { assertTrue(releaseInsert.await(10,TimeUnit.SECONDS),"Test owner did not release the first real insert"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
        };
        if(configuration)mutations.beforeConfigInsert=hold; else mutations.beforeBarcodeInsert=hold;
        List<Future<Outcome>> futures=new ArrayList<>();
        long[] tenants={firstTenant,secondTenant}; List<Supplier<Long>> actions=List.of(first,second);
        try {
            for(int i=0;i<2;i++) {
                final int which=i;
                futures.add(executor.submit(()-> {
                    TenantContextHolder.setTenantId(tenants[which]); var connection=new AtomicLong();
                    try {
                        Long result=transaction().execute(status->{
                            jdbc.execute("SET SESSION innodb_lock_wait_timeout=10");
                            connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)); connections.put(connection.get(),true);
                            ready.countDown();
                            try { assertTrue(start.await(10,TimeUnit.SECONDS)); }
                            catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                            return actions.get(which).get();
                        });
                        return new Outcome(result,null,connection.get());
                    } catch(RuntimeException ex) { return new Outcome(null,ex,connection.get()); }
                    finally { ready.countDown(); TenantContextHolder.clear(); }
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS)); assertEquals(2,connections.size(),"Workers must hold different real connections"); start.countDown();
            assertTrue(atInsert.await(10,TimeUnit.SECONDS),"No actual service reached its database insert");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waited=false;
            while(System.nanoTime()<deadline) {
                List<Object> args=new ArrayList<>(connections.keySet());
                Integer waiting=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_mysql_thread_id IN (?,?)",Integer.class,args.toArray());
                if(waiting!=null&&waiting>0) { waited=true; break; }
                Thread.sleep(20);
            }
            assertTrue(waited,"The other transaction must actually wait on the production mutex, not execute sequentially by chance");
            releaseInsert.countDown();
            var outcomes=new ArrayList<Outcome>(); for(var future:futures)outcomes.add(future.get(15,TimeUnit.SECONDS));
            for(var result:outcomes) if(result.failure()!=null) assertInstanceOf(ServiceException.class,result.failure(),"Only an intended domain rejection is acceptable; no SQL/deadlock failures");
            return outcomes;
        } finally {
            start.countDown(); releaseInsert.countDown(); mutations.beforeBarcodeInsert=()->{}; mutations.beforeConfigInsert=()->{};
            executor.shutdownNow(); assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));
        }
    }
    private void oneWinner(List<Outcome> outcomes,int expectedCode) {
        assertEquals(1,outcomes.stream().filter(o->o.id()!=null).count());
        assertEquals(1,outcomes.stream().filter(o->o.failure() instanceof ServiceException).count());
        assertEquals(expectedCode,((ServiceException)outcomes.stream().filter(o->o.failure()!=null).findFirst().orElseThrow().failure()).getCode());
    }
    @Test void concurrentSameIdentityWithDifferentContentRegistersExactlyOnce() throws Exception {
        oneWinner(race(1,1,()->barcodes.createBarcode(request(1,"IDENTITY-A")),()->barcodes.createBarcode(request(1,"IDENTITY-B")),false),WM_BARCODE_ALREADY_EXISTS.getCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE tenant_id=1 AND deleted=0 AND biz_type=600 AND biz_id=1",Integer.class));
        assertEquals("ITEM-1",jdbc.queryForObject("SELECT biz_code FROM mes_wm_barcode",String.class));
        assertEquals("物料1",jdbc.queryForObject("SELECT biz_name FROM mes_wm_barcode",String.class));
    }
    @Test void concurrentDifferentIdentitiesWithSameContentRegisterExactlyOnce() throws Exception {
        oneWinner(race(1,1,()->barcodes.createBarcode(request(1,"SHARED-CONTENT")),()->barcodes.createBarcode(request(2,"SHARED-CONTENT")),false),WM_BARCODE_CONTENT_DUPLICATE.getCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE tenant_id=1 AND deleted=0 AND content='SHARED-CONTENT'",Integer.class));
    }
    @Test void differentTenantsBothSucceedWithoutGapLockDeadlock() throws Exception {
        var outcomes=race(1,2,()->barcodes.createBarcode(request(1,"SAME-SCAN")),()->barcodes.createBarcode(request(101,"SAME-SCAN")),false);
        assertTrue(outcomes.stream().allMatch(o->o.id()!=null&&o.failure()==null));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE deleted=0 AND content='SAME-SCAN'",Integer.class));
        assertEquals(List.of(1L,2L),jdbc.queryForList("SELECT tenant_id FROM mes_wm_barcode ORDER BY tenant_id",Long.class));
    }
    @Test void concurrentConfigurationCreationAlsoUsesSameCurrentReadMutex() throws Exception {
        oneWinner(race(1,1,()->configs.createBarcodeConfig(config(null,601,0)),()->configs.createBarcodeConfig(config(null,601,0)),true),WM_BARCODE_CONFIG_BIZ_TYPE_DUPLICATE.getCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode_config WHERE tenant_id=1 AND deleted=0 AND biz_type=601",Integer.class));
    }
    private void elsewhere(Runnable work,AtomicLong connection) throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            executor.submit(()-> {
                TenantContextHolder.setTenantId(1L);
                try { transaction().executeWithoutResult(status->{ connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)); work.run(); }); }
                finally { TenantContextHolder.clear(); }
            }).get(10,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void existingRepeatableReadViewCannotHideNewlyCommittedDuplicate() {
        var writerConnection=new AtomicLong(); var oldConnection=new AtomicLong();
        var failure=assertThrows(ServiceException.class,()->transaction().execute(status->{
            oldConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE tenant_id=1 AND deleted=0",Integer.class));
            try { elsewhere(()->barcodes.createBarcode(request(1,"NEW-COMMITTED")),writerConnection); }
            catch(Exception ex) { throw new IllegalStateException(ex); }
            assertNotEquals(oldConnection.get(),writerConnection.get());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE tenant_id=1 AND deleted=0",Integer.class),"Old RR view must still be empty");
            return barcodes.createBarcode(request(1,"DIFFERENT-CONTENT"));
        }));
        assertEquals(WM_BARCODE_ALREADY_EXISTS.getCode(),failure.getCode()); assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode WHERE deleted=0",Integer.class));
        assertEquals("NEW-COMMITTED",jdbc.queryForObject("SELECT content FROM mes_wm_barcode",String.class));
    }
    @Test void existingRepeatableReadViewCannotIgnoreCommittedConfigurationDisable() {
        var writerConnection=new AtomicLong(); var oldConnection=new AtomicLong();
        var failure=assertThrows(ServiceException.class,()->transaction().execute(status->{
            oldConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT status FROM mes_wm_barcode_config WHERE id=1",Integer.class));
            try { elsewhere(()->configs.updateBarcodeConfig(config(1L,600,1)),writerConnection); }
            catch(Exception ex) { throw new IllegalStateException(ex); }
            assertNotEquals(oldConnection.get(),writerConnection.get());
            assertEquals(0,jdbc.queryForObject("SELECT status FROM mes_wm_barcode_config WHERE id=1",Integer.class),"Old RR snapshot must retain the old enabled state");
            return barcodes.createBarcode(request(1,"SHOULD-NOT-REGISTER"));
        }));
        assertTrue(failure.getMessage().contains("禁用")); assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_barcode",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM mes_wm_barcode_config WHERE id=1",Integer.class));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Configuration {
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean MutationAdapter mutationAdapter(JdbcTemplate jdbc) { return new MutationAdapter(jdbc); }
        @Bean MesWmBarcodeMapper barcodeMapper(MutationAdapter adapter) { return adapter.barcodes(); }
        @Bean MesWmBarcodeConfigMapper barcodeConfigMapper(MutationAdapter adapter) { return adapter.configs(); }
        @Bean MesWmBarcodeBusinessResolver businessResolver(JdbcTemplate jdbc) { return new MesWmBarcodeBusinessResolver(jdbc); }
        @Bean MesWmBarcodeMutex mutex() { return new MesWmBarcodeMutex(); }
        @Bean MesWmBarcodeRecords records() { return new MesWmBarcodeRecords(); }
        @Bean MesWmBarcodeService barcodeService() { return new MesWmBarcodeServiceImpl(); }
        @Bean MesWmBarcodeConfigService barcodeConfigService() { return new MesWmBarcodeConfigServiceImpl(); }
    }
    static class MutationAdapter {
        final JdbcTemplate jdbc;
        volatile Runnable beforeBarcodeInsert=()->{};
        volatile Runnable beforeConfigInsert=()->{};
        MutationAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        private long insert(String sql,Object...values) {
            var keys=new GeneratedKeyHolder();
            jdbc.update(connection->{
                var statement=connection.prepareStatement(sql,new String[]{"id"});
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]); return statement;
            },keys);
            return Objects.requireNonNull(keys.getKey()).longValue();
        }
        MesWmBarcodeMapper barcodes() {
            var mapper=mock(MesWmBarcodeMapper.class);
            doAnswer(call->{
                MesWmBarcodeDO row=call.getArgument(0); beforeBarcodeInsert.run();
                row.setId(insert("INSERT INTO mes_wm_barcode(config_id,format,biz_type,content,biz_id,biz_code,biz_name,status,remark,tenant_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        row.getConfigId(),row.getFormat(),row.getBizType(),row.getContent(),row.getBizId(),row.getBizCode(),row.getBizName(),row.getStatus(),row.getRemark(),TenantContextHolder.getRequiredTenantId())); return 1;
            }).when(mapper).insert(any(MesWmBarcodeDO.class));
            doAnswer(call->{MesWmBarcodeDO row=call.getArgument(0);return jdbc.update("UPDATE mes_wm_barcode SET config_id=?,format=?,biz_type=?,content=?,biz_id=?,biz_code=?,biz_name=?,status=?,remark=? WHERE id=? AND tenant_id=? AND deleted=0",
                    row.getConfigId(),row.getFormat(),row.getBizType(),row.getContent(),row.getBizId(),row.getBizCode(),row.getBizName(),row.getStatus(),row.getRemark(),row.getId(),TenantContextHolder.getRequiredTenantId());}).when(mapper).updateById(any(MesWmBarcodeDO.class));
            doAnswer(call->jdbc.update("UPDATE mes_wm_barcode SET deleted=1 WHERE id=? AND tenant_id=?",call.getArgument(0),TenantContextHolder.getRequiredTenantId())).when(mapper).deleteById(any(Serializable.class));
            return mapper;
        }
        MesWmBarcodeConfigMapper configs() {
            var mapper=mock(MesWmBarcodeConfigMapper.class);
            doAnswer(call->{
                MesWmBarcodeConfigDO row=call.getArgument(0); beforeConfigInsert.run();
                row.setId(insert("INSERT INTO mes_wm_barcode_config(format,biz_type,content_format,content_example,auto_generate_flag,default_template,status,remark,tenant_id) VALUES(?,?,?,?,?,?,?,?,?)",
                        row.getFormat(),row.getBizType(),row.getContentFormat(),row.getContentExample(),row.getAutoGenerateFlag(),row.getDefaultTemplate(),row.getStatus(),row.getRemark(),TenantContextHolder.getRequiredTenantId())); return 1;
            }).when(mapper).insert(any(MesWmBarcodeConfigDO.class));
            doAnswer(call->{MesWmBarcodeConfigDO row=call.getArgument(0);return jdbc.update("UPDATE mes_wm_barcode_config SET format=?,biz_type=?,content_format=?,content_example=?,auto_generate_flag=?,default_template=?,status=?,remark=? WHERE id=? AND tenant_id=? AND deleted=0",
                    row.getFormat(),row.getBizType(),row.getContentFormat(),row.getContentExample(),row.getAutoGenerateFlag(),row.getDefaultTemplate(),row.getStatus(),row.getRemark(),row.getId(),TenantContextHolder.getRequiredTenantId());}).when(mapper).updateById(any(MesWmBarcodeConfigDO.class));
            doAnswer(call->jdbc.update("UPDATE mes_wm_barcode_config SET deleted=1 WHERE id=? AND tenant_id=?",call.getArgument(0),TenantContextHolder.getRequiredTenantId())).when(mapper).deleteById(any(Serializable.class));
            return mapper;
        }
    }
}
