package cn.iocoder.yudao.module.mes.service.wm;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Regression across the other nine document types without a database or live stock mutations. */
class MesWmDocumentExecutionGuardTest {
    static final String ROOT="cn.iocoder.yudao.module.mes.";
    record Case(String module,String name,String prefix,String key,String finish) {}
    static final List<Case> CASES=List.of(
        new Case("itemreceipt","ItemReceipt","itemReceipt","Receipt","finishItemReceipt"),
        new Case("productsales","ProductSales","productSales","Sales","finishProductSales"),
        new Case("returnvendor","ReturnVendor","returnVendor","Return","finishReturnVendor"),
        new Case("returnsales","ReturnSales","returnSales","Return","stockReturnSales"),
        new Case("transfer","Transfer","transfer","Transfer","finishTransfer"),
        new Case("outsourceissue","OutsourceIssue","outsourceIssue","Issue","finishOutsourceIssue"),
        new Case("outsourcereceipt","OutsourceReceipt","outsourceReceipt","Receipt","finishOutsourceReceipt"),
        new Case("miscreceipt","MiscReceipt","miscReceipt","Receipt","finishMiscReceipt"),
        new Case("miscissue","MiscIssue","miscIssue","Issue","finishMiscIssue"));
    Class<?> type(String relative) throws Exception {return Class.forName(ROOT+relative);}
    Object data(Case c,String suffix) throws Exception {return type("dal.dataobject.wm."+c.module()+".MesWm"+c.name()+suffix+"DO").getConstructor().newInstance();}
    void set(Object bean,String field,Class<?> kind,Object value) throws Exception {
        bean.getClass().getMethod("set"+field,kind).invoke(bean,value);
    }
    Object service(Case c) throws Exception {return type("service.wm."+c.module()+".MesWm"+c.name()+"ServiceImpl").getConstructor().newInstance();}
    Object stubParent(Case c,Object service,int status) throws Exception {
        var mapperType=type("dal.mysql.wm."+c.module()+".MesWm"+c.name()+"Mapper");
        Object mapper=mock(mapperType),document=data(c,"");set(document,"Id",Long.class,1L);set(document,"Status",Integer.class,status);
        when(mapperType.getMethod("selectForUpdate",Long.class).invoke(mapper,1L)).thenReturn(document);
        ReflectionTestUtils.setField(service,c.prefix()+"Mapper",mapper);return mapper;
    }
    void rejectedBeforeMovement(Case c,Object service) throws Exception {
        var failure=assertThrows(InvocationTargetException.class,()->service.getClass().getMethod(c.finish(),Long.class).invoke(service,1L));
        assertInstanceOf(ServiceException.class,failure.getCause());
    }
    @TestFactory Stream<DynamicTest> completedDocumentCannotExecuteAgainAndUsesCurrentRead() {
        return CASES.stream().map(c->DynamicTest.dynamicTest(c.module()+" current-state guard",()->{
            var service=service(c);var mapper=stubParent(c,service,4);
            rejectedBeforeMovement(c,service);
            mapper.getClass().getMethod("selectForUpdate",Long.class).invoke(verify(mapper),1L);
        }));
    }
    @TestFactory Stream<DynamicTest> finalMovementRejectsPartialOrExcessDetailAllocation() {
        return CASES.stream().filter(c->!Set.of("returnvendor","miscreceipt","miscissue").contains(c.module())).map(c->
            DynamicTest.dynamicTest(c.module()+" final quantity guard",()->{
                var service=service(c);stubParent(c,service,3);
                Object line=data(c,"Line"),detail=data(c,"Detail");
                set(line,"Id",Long.class,10L);set(line,c.key()+"Id",Long.class,1L);set(line,"ItemId",Long.class,100L);
                set(line,c.module().equals("itemreceipt")?"ReceivedQuantity":"Quantity",BigDecimal.class,new BigDecimal("10"));
                set(detail,"Id",Long.class,20L);set(detail,"LineId",Long.class,10L);set(detail,c.key()+"Id",Long.class,1L);set(detail,"ItemId",Long.class,100L);
                // Returnsales formerly accepted over-allocation; the other paths formerly accepted partial allocation.
                set(detail,"Quantity",BigDecimal.class,new BigDecimal(c.module().equals("returnsales")?"11":"1"));
                Class<?> lineType=type("service.wm."+c.module()+".MesWm"+c.name()+"LineService");
                Class<?> detailType=type("service.wm."+c.module()+".MesWm"+c.name()+"DetailService");
                Object lineService=mock(lineType),detailService=mock(detailType);
                when(lineType.getMethod("get"+c.name()+"LineListBy"+c.key()+"Id",Long.class).invoke(lineService,1L)).thenReturn(List.of(line));
                when(detailType.getMethod("get"+c.name()+"DetailListBy"+c.key()+"Id",Long.class).invoke(detailService,1L)).thenReturn(List.of(detail));
                ReflectionTestUtils.setField(service,c.prefix()+"LineService",lineService);
                ReflectionTestUtils.setField(service,c.prefix()+"DetailService",detailService);
                rejectedBeforeMovement(c,service);
            }));
    }
    @TestFactory Stream<DynamicTest> changingRequestParentCannotMoveLineOutOfClosedDocument() {
        Map<String,String> validators=Map.of(
            "itemreceipt","validateItemReceiptEditable","productsales","validateProductSalesExistsAndDraft",
            "returnvendor","validateReturnVendorExistsAndPrepare","returnsales","validateReturnSalesExistsAndPrepare",
            "transfer","validateTransferEditable","miscreceipt","validateMiscReceiptEditable","miscissue","validateMiscIssueEditable");
        return CASES.stream().filter(c->validators.containsKey(c.module())).map(c->DynamicTest.dynamicTest(c.module()+" immutable line parent",()->{
            Class<?> serviceType=type("service.wm."+c.module()+".MesWm"+c.name()+"LineServiceImpl");
            Object service=serviceType.getConstructor().newInstance();Object line=data(c,"Line");
            set(line,"Id",Long.class,10L);set(line,c.key()+"Id",Long.class,100L);
            Class<?> mapperType=type("dal.mysql.wm."+c.module()+".MesWm"+c.name()+"LineMapper");
            Object mapper=mock(mapperType,inv->inv.getMethod().getName().equals("selectById")?line:RETURNS_DEFAULTS.answer(inv));
            ReflectionTestUtils.setField(service,c.prefix()+"LineMapper",mapper);
            Class<?> parentType=type("service.wm."+c.module()+".MesWm"+c.name()+"Service");Object parent=mock(parentType);
            parentType.getMethod(validators.get(c.module()),Long.class).invoke(doThrow(new ServiceException(990001,"closed document")).when(parent),100L);
            ReflectionTestUtils.setField(service,c.prefix()+"Service",parent);
            Class<?> requestType=type("controller.admin.wm."+c.module()+".vo.line.MesWm"+c.name()+"LineSaveReqVO");
            Object req=requestType.getConstructor().newInstance();set(req,"Id",Long.class,10L);set(req,c.key()+"Id",Long.class,999L);
            var failure=assertThrows(InvocationTargetException.class,()->serviceType.getMethod("update"+c.name()+"Line",requestType).invoke(service,req));
            assertInstanceOf(ServiceException.class,failure.getCause(),()->{
                var trace=new java.io.StringWriter();
                failure.getCause().printStackTrace(new java.io.PrintWriter(trace));
                return c.module()+" unexpected failure: "+trace;
            });
            assertEquals(100L,requestType.getMethod("get"+c.key()+"Id").invoke(req));
            parentType.getMethod(validators.get(c.module()),Long.class).invoke(verify(parent),100L);
            assertTrue(mockingDetails(mapper).getInvocations().stream()
                .noneMatch(inv->inv.getMethod().getName().equals("updateById")),
                "A closed parent must be rejected before any line update");
        }));
    }
}
