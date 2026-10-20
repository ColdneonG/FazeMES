package cn.iocoder.yudao.module.mes.service.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

class MesModelUsageServiceTest extends MesAssistantTestDatabase {
    @Test void aCommittedReservationUsesAllEightPersistedBuckets(){
        var meter=new MesModelUsageService(jdbc,json,new DataSourceTransactionManager(jdbc.getDataSource()));
        var reservation=meter.reserve(MesModelUsageService.Purpose.KNOWLEDGE_CHAT,"CHAT","openai","test-model",128,128);
        assertNotNull(reservation.id());assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM mes_model_usage_bucket",Integer.class));
        meter.dispatched(reservation.id());meter.settle(reservation.id(),new MesModelUsageService.Settlement("SUCCESS",1L,1L,2L,"test-model",200,1,true,false));
        assertEquals("2",((java.util.Map<?,?>)((java.util.Map<?,?>)((java.util.Map<?,?>)meter.summary().get("tenant")).get("day")).get("tokens")).get("used"));
    }
}
