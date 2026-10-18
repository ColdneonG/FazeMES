package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MesProductionDashboardSerializationTest {
    private ObjectMapper timestampMapper() {
        var module=new SimpleModule().addSerializer(LocalDateTime.class,TimestampLocalDateTimeSerializer.INSTANCE);
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(module)
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test void globalTimestampModeCannotTurnDashboardDaysIntoNumbersOrArrays() throws Exception {
        var date=LocalDate.of(2026,10,18);
        var time=date.atTime(8,30,12);
        Map<String,Object> raw=new LinkedHashMap<>();
        raw.put("date",date);raw.put("updatedAt",time);
        raw.put("trend",List.of(Map.of("day",java.sql.Date.valueOf(date),"produced",new BigDecimal("12.50"))));
        Map<String,Object> task=new LinkedHashMap<>();
        task.put("startTime",java.sql.Timestamp.valueOf(time));task.put("endTime",null);
        task.put("planned",Map.of("date",date,"time",LocalTime.of(9,0)));
        raw.put("tasks",List.of(task));raw.put("target",new BigDecimal("20.00"));raw.put("shortageAvailable",true);
        var mapper=timestampMapper();
        // Prove that this test actually enables the same incompatible global wire formats.
        var before=mapper.readTree(mapper.writeValueAsString(raw));
        assertTrue(before.get("date").isArray());assertTrue(before.get("updatedAt").isNumber());
        assertTrue(before.get("trend").get(0).get("day").isNumber());

        var response=MesReportTimeFormat.isoResponse(raw);
        var json=mapper.readTree(mapper.writeValueAsString(response));
        assertEquals("2026-10-18",json.get("date").asText());
        assertEquals("2026-10-18T08:30:12",json.get("updatedAt").asText());
        assertEquals("2026-10-18",json.get("trend").get(0).get("day").asText());
        assertEquals("2026-10-18T08:30:12",json.get("tasks").get(0).get("startTime").asText());
        assertTrue(json.get("tasks").get(0).get("endTime").isNull());
        assertEquals("2026-10-18",json.get("tasks").get(0).get("planned").get("date").asText());
        assertEquals("09:00:00",json.get("tasks").get(0).get("planned").get("time").asText());
        assertTrue(json.get("target").isNumber());assertTrue(json.get("shortageAvailable").asBoolean());
        assertEquals(0,new BigDecimal("12.50").compareTo(json.get("trend").get(0).get("produced").decimalValue()));
    }

    @Test void jdbcAndJavaTimeValuesUseTheSameDayAndSecondPrecision() {
        var date=LocalDate.of(2026,10,18);var time=date.atTime(7,5,0);
        var response=MesReportTimeFormat.isoResponse(Map.of("javaDate",date,"sqlDate",java.sql.Date.valueOf(date),
                "javaTime",time,"sqlTimestamp",java.sql.Timestamp.valueOf(time),"sqlTime",java.sql.Time.valueOf("07:05:00")));
        assertEquals(response.get("javaDate"),response.get("sqlDate"));assertEquals("2026-10-18",response.get("sqlDate"));
        assertEquals(response.get("javaTime"),response.get("sqlTimestamp"));assertEquals("2026-10-18T07:05:00",response.get("javaTime"));
        assertEquals("07:05:00",response.get("sqlTime"));
    }
}
