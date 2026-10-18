package cn.iocoder.yudao.module.mes.service.report;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit ISO wire values for JDBC/Map reports, independent of global Jackson timestamp settings. */
public final class MesReportTimeFormat {
    private MesReportTimeFormat() {}

    public static Map<String,Object> isoResponse(Map<String,Object> response) {
        Map<String,Object> normalized=new LinkedHashMap<>();
        response.forEach((key,value)->normalized.put(key,isoValue(value)));
        return normalized;
    }

    public static Object isoValue(Object value) {
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if(value instanceof java.sql.Date date)return date.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if(value instanceof java.sql.Time time)return time.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        if(value instanceof LocalDateTime dateTime)return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if(value instanceof LocalDate date)return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if(value instanceof LocalTime time)return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        if(value instanceof Map<?,?> map){Map<Object,Object> normalized=new LinkedHashMap<>();map.forEach((key,item)->normalized.put(key,isoValue(item)));return normalized;}
        if(value instanceof List<?> list)return list.stream().map(MesReportTimeFormat::isoValue).toList();
        return value;
    }
}
