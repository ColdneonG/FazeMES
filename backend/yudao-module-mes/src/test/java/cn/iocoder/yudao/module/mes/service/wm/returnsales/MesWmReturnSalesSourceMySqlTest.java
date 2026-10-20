package cn.iocoder.yudao.module.mes.service.wm.returnsales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.wm.returnsales.vo.line.MesWmReturnSalesLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdItemDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.returnsales.*;
import cn.iocoder.yudao.module.mes.service.md.item.MesMdItemService;
import cn.iocoder.yudao.module.mes.service.wm.batch.MesWmBatchService;
import cn.iocoder.yudao.module.mes.service.wm.transaction.dto.MesWmTransactionSaveReqDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real independent InnoDB source mutexes/counters with actual create/preflight/posting-account services. */
@EnabledIfEnvironmentVariable(named="MES_RETURN_SOURCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:59685/return_source_it_[A-Za-z0-9_]+(?:\\?.*)?")
class MesWmReturnSalesSourceMySqlTest {
    private DataSource dataSource;private JdbcTemplate jdbc;private TransactionTemplate tx;
    private MesWmReturnSalesSourceService sources;private MesWmReturnSalesLineServiceImpl lines;
    private String schema;private boolean owned;private final AtomicLong lineIds=new AtomicLong(100);
    private volatile CyclicBarrier reservationInsertBarrier;
    private static final Set<String> TABLES=Set.of("mes_wm_batch","mes_wm_product_sales","mes_wm_product_sales_line","mes_wm_product_sales_detail","mes_wm_transaction","mes_wm_return_sales","mes_wm_return_sales_line","mes_wm_return_sales_detail","mes_wm_return_source_budget","mes_wm_return_source_reservation","mes_wm_return_posted_evidence","mes_qc_rqc");
    @BeforeEach void setup(){
        TenantContextHolder.setTenantId(0L);String url=System.getenv("MES_RETURN_SOURCE_MYSQL_URL");URI u=URI.create(url.substring(5));assertEquals("127.0.0.1",u.getHost());assertEquals(59685,u.getPort());schema=u.getPath().substring(1);assertTrue(schema.matches("return_source_it_[A-Za-z0-9_]+"));
        dataSource=new DriverManagerDataSource(url,System.getenv("MES_RETURN_SOURCE_MYSQL_USER"),System.getenv("MES_RETURN_SOURCE_MYSQL_PASSWORD"));jdbc=new JdbcTemplate(dataSource);
        assertTrue(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",schema).isEmpty(),"Dedicated EMPTY schema only");owned=true;
        String audit=",tenant_id BIGINT NOT NULL DEFAULT 0,deleted BIT NOT NULL DEFAULT 0";
        jdbc.execute("CREATE TABLE mes_wm_batch(id BIGINT PRIMARY KEY,item_id BIGINT,code VARCHAR(64),client_id BIGINT"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales(id BIGINT PRIMARY KEY,client_id BIGINT,status INT,code VARCHAR(64)"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_line(id BIGINT PRIMARY KEY,sales_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),quality_status INT"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_product_sales_detail(id BIGINT PRIMARY KEY,sales_id BIGINT,line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,material_stock_id BIGINT"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,type INT,biz_type INT,biz_id BIGINT,biz_line_id BIGINT,biz_code VARCHAR(64),item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT,material_stock_id BIGINT,return_source_transaction_id BIGINT,return_detail_id BIGINT"+audit+",KEY idx_origin(tenant_id,biz_type,return_source_transaction_id,id),UNIQUE KEY uk_detail(tenant_id,biz_type,return_detail_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_sales(id BIGINT PRIMARY KEY,client_id BIGINT,status INT,code VARCHAR(64)"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_sales_line(id BIGINT PRIMARY KEY,return_id BIGINT,origin_transaction_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),quality_status INT,rqc_check_flag BIT,rqc_id BIGINT"+audit+",KEY idx_parent(return_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_sales_detail(id BIGINT PRIMARY KEY,return_id BIGINT,line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,batch_code VARCHAR(64),warehouse_id BIGINT,location_id BIGINT,area_id BIGINT"+audit+") ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_source_budget(tenant_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,reserved_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,returned_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id,origin_transaction_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_source_reservation(tenant_id BIGINT NOT NULL,return_line_id BIGINT NOT NULL,return_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,quantity DECIMAL(20,6) NOT NULL,posted_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,state VARCHAR(16),update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,return_line_id),KEY idx_origin(tenant_id,origin_transaction_id,state,return_line_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_wm_return_posted_evidence(tenant_id BIGINT NOT NULL,return_transaction_id BIGINT NOT NULL,return_id BIGINT NOT NULL,return_line_id BIGINT NOT NULL,return_detail_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,quantity DECIMAL(20,6) NOT NULL,PRIMARY KEY(tenant_id,return_transaction_id),UNIQUE KEY uk_detail(tenant_id,return_detail_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mes_qc_rqc(id BIGINT PRIMARY KEY,source_doc_type INT,source_doc_id BIGINT,source_line_id BIGINT"+audit+") ENGINE=InnoDB");
        tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(15);
        var headerMapper=mock(MesWmReturnSalesMapper.class);when(headerMapper.selectForUpdate(anyLong())).thenAnswer(c->parent(c.getArgument(0),true));when(headerMapper.selectById(anyLong())).thenAnswer(c->parent(c.getArgument(0),false));
        var mapper=mock(MesWmReturnSalesLineMapper.class);when(mapper.selectById(anyLong())).thenAnswer(c->line(c.getArgument(0),false));when(mapper.selectByIdForUpdate(anyLong())).thenAnswer(c->line(c.getArgument(0),true));
        when(mapper.selectListByReturnId(anyLong())).thenAnswer(c->lineList(c.getArgument(0),false));when(mapper.selectListByReturnIdForUpdate(anyLong())).thenAnswer(c->lineList(c.getArgument(0),true));
        when(mapper.insert(any(MesWmReturnSalesLineDO.class))).thenAnswer(c->{MesWmReturnSalesLineDO l=c.getArgument(0);l.setId(lineIds.incrementAndGet());return jdbc.update("INSERT INTO mes_wm_return_sales_line(id,return_id,origin_transaction_id,item_id,quantity,batch_id,batch_code,quality_status,rqc_check_flag) VALUES(?,?,?,?,?,?,?,?,?)",l.getId(),l.getReturnId(),l.getOriginTransactionId(),l.getItemId(),l.getQuantity(),l.getBatchId(),l.getBatchCode(),l.getQualityStatus(),l.getRqcCheckFlag());});
        var items=mock(MesMdItemService.class);when(items.validateItemExistsAndEnable(7L)).thenReturn(new MesMdItemDO().setId(7L).setBatchFlag(true));var batches=mock(MesWmBatchService.class);when(batches.validateBatchExists(9L,7L)).thenReturn(new MesWmBatchDO().setId(9L).setItemId(7L).setCode("B9"));
        var delivery=new MesWmReturnSalesDeliveryService();inject(delivery,"jdbcTemplate",jdbc,"itemService",items,"batchService",batches);
        var sourceJdbc=new JdbcTemplate(dataSource){@Override public int update(String sql,Object...args){if(reservationInsertBarrier!=null&&sql.startsWith("INSERT IGNORE INTO mes_wm_return_source_reservation")){try{reservationInsertBarrier.await(6,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}}return super.update(sql,args);}};
        sources=new MesWmReturnSalesSourceService();inject(sources,"jdbcTemplate",sourceJdbc,"returnSalesMapper",headerMapper,"returnSalesLineMapper",mapper,"deliveryService",delivery);
        var returns=mock(MesWmReturnSalesService.class);when(returns.validateReturnSalesExistsAndPrepare(anyLong())).thenAnswer(c->{var p=sources.lockParent(c.getArgument(0));if(p.getStatus()!=0)throw new IllegalStateException("not draft");return p;});
        lines=new MesWmReturnSalesLineServiceImpl();inject(lines,"returnSalesLineMapper",mapper,"returnSalesService",returns,"sourceService",sources,"deliveryService",delivery,"itemService",items,"batchService",batches,"jdbcTemplate",jdbc);
        jdbc.update("INSERT INTO mes_wm_batch(id,item_id,code) VALUES(9,7,'B9')");jdbc.update("INSERT INTO mes_wm_product_sales(id,client_id,status,code) VALUES(20,10,4,'SALE')");
        jdbc.update("INSERT INTO mes_wm_product_sales_line(id,sales_id,item_id,quantity,batch_id,batch_code,quality_status) VALUES(21,20,7,20,9,'B9',1)");
        jdbc.update("INSERT INTO mes_wm_product_sales_detail(id,sales_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(22,20,21,7,20,9,'B9',2,3,4,8)");
        for(long id:List.of(23L,24L))jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,material_stock_id) VALUES(?,2,118,20,21,7,-10,9,'B9',2,3,4,8)",id);
        jdbc.update("INSERT INTO mes_wm_return_sales(id,client_id,status,code) VALUES(1,10,0,'RET1'),(2,10,0,'RET2')");
    }
    @AfterEach void cleanup(){TenantContextHolder.clear();if(!owned)return;var names=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=?",String.class,schema);assertTrue(TABLES.containsAll(names),"Unexpected table: refuse cleanup");for(String name:names)jdbc.execute("DROP TABLE `"+name+"`");}
    @Test void sameOriginalLastCapacityTwoOldViewsOnlyOneCanReserveSixOfTen()throws Exception{var result=race(23,23,"6");assertEquals(1,result.stream().filter(Objects::isNull).count());assertEquals(1,result.stream().filter(ServiceException.class::isInstance).count());assertEquals(0,new BigDecimal("6").compareTo(jdbc.queryForObject("SELECT reserved_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23",BigDecimal.class)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_sales_line",Integer.class));}
    @Test void differentOriginalsFirstReservationsDoNotHoldEmptyRangeGaps()throws Exception{reservationInsertBarrier=new CyclicBarrier(2);var result=race(23,24,"10");assertTrue(result.stream().allMatch(Objects::isNull),result.toString());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_source_reservation",Integer.class));}
    @Test void cancelReleasesOnlyUnpostedCounterAndOtherDraftCanUseIt(){Long first=inTx(()->lines.createReturnSalesLine(req(1,23,"10")));inTx(()->{sources.lockParent(1L);sources.lockOriginIds(List.of(23L));sources.releaseLine(first);jdbc.update("UPDATE mes_wm_return_sales SET status=5 WHERE id=1");return null;});assertDoesNotThrow(()->inTx(()->lines.createReturnSalesLine(req(2,23,"10"))));assertEquals(0,new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT reserved_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23",BigDecimal.class)));}
    @Test void oldRrViewCannotIgnoreNewlyCommitted119CounterButFreshViewCanUseRemaining()throws Exception{
        var ready=new CountDownLatch(1);var posted=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();try{
            var stale=pool.submit(()->attempt(()->{jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_transaction",Integer.class);ready.countDown();await(posted);lines.createReturnSalesLine(req(2,23,"1"));}));assertTrue(ready.await(5,TimeUnit.SECONDS));
            inTx(()->{Long id=lines.createReturnSalesLine(req(1,23,"4"));jdbc.update("UPDATE mes_wm_return_sales SET status=3 WHERE id=1");post(id,23L,new BigDecimal("4"));sources.markPosted(1L,List.of(id));jdbc.update("UPDATE mes_wm_return_sales SET status=4 WHERE id=1");return null;});posted.countDown();assertInstanceOf(ServiceException.class,stale.get(10,TimeUnit.SECONDS));assertDoesNotThrow(()->inTx(()->lines.createReturnSalesLine(req(2,23,"1"))));
        }finally{posted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void finalStateUsesCurrentLineIdsEvenWhenRrSnapshotPredatesReservation()throws Exception{
        var ready=new CountDownLatch(1);var posted=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();var currentLineId=new AtomicLong();try{
            var finalizer=pool.submit(()->attempt(()->{assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_source_reservation",Integer.class));ready.countDown();await(posted);sources.lockParent(1L);sources.lockOriginIds(List.of(23L));var currentLines=lineList(1L,true);assertEquals(1,currentLines.size());assertEquals(currentLineId.get(),currentLines.get(0).getId());sources.markPosted(1L,currentLines.stream().map(MesWmReturnSalesLineDO::getId).toList());}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));inTx(()->{Long id=lines.createReturnSalesLine(req(1,23,"4"));currentLineId.set(id);jdbc.update("UPDATE mes_wm_return_sales SET status=3 WHERE id=1");post(id,23L,new BigDecimal("4"));return null;});posted.countDown();assertNull(finalizer.get(10,TimeUnit.SECONDS));
            assertEquals("POSTED",jdbc.queryForObject("SELECT state FROM mes_wm_return_source_reservation WHERE return_line_id=?",String.class,currentLineId.get()));assertEquals(0,new BigDecimal("4").compareTo(jdbc.queryForObject("SELECT returned_quantity FROM mes_wm_return_source_budget WHERE origin_transaction_id=23",BigDecimal.class)));
        }finally{posted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void sourceProofAndBudgetDoNotLockHistoricalShipmentOr118()throws Exception{inTx(()->{sources.lockParent(1L);sources.lockOriginIds(List.of(23L));sources.origin(10L,23L);try(var connection=dataSource.getConnection()){connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SET innodb_lock_wait_timeout=1");for(String sql:List.of("SELECT id FROM mes_wm_product_sales WHERE id=20 FOR UPDATE","SELECT id FROM mes_wm_product_sales_line WHERE id=21 FOR UPDATE","SELECT id FROM mes_wm_product_sales_detail WHERE id=22 FOR UPDATE","SELECT id FROM mes_wm_transaction WHERE id=23 FOR UPDATE"))try(var result=statement.executeQuery(sql)){assertTrue(result.next());}}finally{connection.rollback();}}return null;});}
    private List<Throwable> race(long origin1,long origin2,String quantity)throws Exception{var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(()->attempt(()->{jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_source_reservation",Integer.class);ready.countDown();await(start);lines.createReturnSalesLine(req(1,origin1,quantity));}));var second=pool.submit(()->attempt(()->{jdbc.queryForObject("SELECT COUNT(*) FROM mes_wm_return_source_reservation",Integer.class);ready.countDown();await(start);lines.createReturnSalesLine(req(2,origin2,quantity));}));assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();return Arrays.asList(first.get(12,TimeUnit.SECONDS),second.get(12,TimeUnit.SECONDS));}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
    private void post(Long lineId,Long origin,BigDecimal amount){jdbc.update("INSERT INTO mes_wm_return_sales_detail(id,return_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id) VALUES(7001,1,?,7,?,9,'B9',2,3,4)",lineId,amount);var request=new MesWmTransactionSaveReqDTO().setBizType(119).setBizId(1L).setBizCode("RET1").setBizLineId(lineId).setType(1).setItemId(7L).setBatchId(9L).setBatchCode("B9").setQuantity(amount).setReturnSourceTransactionId(origin).setReturnDetailId(7001L).setWarehouseId(2L).setLocationId(3L).setAreaId(4L);sources.preflight(List.of(request));jdbc.update("INSERT INTO mes_wm_transaction(id,type,biz_type,biz_id,biz_line_id,biz_code,item_id,quantity,batch_id,batch_code,return_source_transaction_id,return_detail_id) VALUES(10001,1,119,1,?,'RET1',7,?,9,'B9',?,7001)",lineId,amount,origin);sources.recordPosted(new MesWmTransactionDO().setId(10001L).setBizType(119).setBizId(1L).setBizLineId(lineId).setQuantity(amount).setReturnSourceTransactionId(origin).setReturnDetailId(7001L));}
    private MesWmReturnSalesLineSaveReqVO req(long parent,long origin,String quantity){var r=new MesWmReturnSalesLineSaveReqVO();r.setReturnId(parent);r.setOriginTransactionId(origin);r.setQuantity(new BigDecimal(quantity));r.setRqcCheckFlag(false);return r;}
    private MesWmReturnSalesDO parent(Long id,boolean current){var result=jdbc.query("SELECT * FROM mes_wm_return_sales WHERE id=?"+(current?" FOR UPDATE":""),(r,n)->new MesWmReturnSalesDO().setId(r.getLong("id")).setClientId(r.getLong("client_id")).setCode(r.getString("code")).setStatus(r.getInt("status")),id);return result.isEmpty()?null:result.get(0);}
    private MesWmReturnSalesLineDO line(Long id,boolean current){var result=jdbc.query("SELECT * FROM mes_wm_return_sales_line WHERE id=?"+(current?" FOR UPDATE":""),this::lineRow,id);return result.isEmpty()?null:result.get(0);}
    private List<MesWmReturnSalesLineDO> lineList(Long parent,boolean current){return jdbc.query("SELECT * FROM mes_wm_return_sales_line WHERE return_id=? ORDER BY id"+(current?" FOR UPDATE":""),this::lineRow,parent);}
    private MesWmReturnSalesLineDO lineRow(ResultSet r,int n)throws SQLException{return new MesWmReturnSalesLineDO().setId(r.getLong("id")).setReturnId(r.getLong("return_id")).setOriginTransactionId((Long)r.getObject("origin_transaction_id")).setItemId(r.getLong("item_id")).setQuantity(r.getBigDecimal("quantity")).setBatchId((Long)r.getObject("batch_id")).setBatchCode(r.getString("batch_code")).setQualityStatus(r.getInt("quality_status"));}
    private <T>T inTx(Callable<T> call){TenantContextHolder.setTenantId(0L);return tx.execute(status->{try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});}
    private Throwable attempt(Runnable action){try{inTx(()->{action.run();return null;});return null;}catch(Throwable e){return e;}finally{TenantContextHolder.clear();}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static void inject(Object target,Object...fields){for(int i=0;i<fields.length;i+=2)ReflectionTestUtils.setField(target,(String)fields[i],fields[i+1]);}
}
