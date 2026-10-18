package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only order counts. Free-text client addresses never leave this service. */
@Service
public class MesDashboardRegionService {

    private static final String BASIS = "按所选日期交叠且未取消（状态非5）的任务范围，按生产工单去重计数；遵循车间、工位筛选。"
            + "地域仅由客户地址中明确的单一标准城市名离线识别；无客户、地址缺失、未知或歧义地址计入未定位。"
            + "坐标为城市中心示意，不代表客户精确位置。";
    private static final Pattern AMBIGUOUS_ADDRESS = Pattern.compile("[或/／;；]|以及|待定|不详|未知|待确认|可能");
    private static final Pattern STREET_SUFFIX = Pattern.compile("^(路|街|大道|大街|巷|弄).*");
    private static final List<City> CITIES = List.of(
            new City("北京市", "", 116.4074, 39.9042),
            new City("上海市", "", 121.4737, 31.2304),
            new City("广州市", "广东省", 113.2644, 23.1291),
            new City("深圳市", "广东省", 114.0579, 22.5431),
            new City("杭州市", "浙江省", 120.1551, 30.2741),
            new City("南京市", "江苏省", 118.7969, 32.0603),
            new City("成都市", "四川省", 104.0668, 30.5728),
            new City("重庆市", "", 106.5516, 29.5630),
            new City("武汉市", "湖北省", 114.3054, 30.5928),
            new City("西安市", "陕西省", 108.9398, 34.3416),
            new City("天津市", "", 117.2009, 39.0842),
            new City("苏州市", "江苏省", 120.5853, 31.2989),
            new City("青岛市", "山东省", 120.3826, 36.0671),
            new City("沈阳市", "辽宁省", 123.4315, 41.8057),
            new City("长沙市", "湖南省", 112.9388, 28.2282),
            new City("大连市", "辽宁省", 121.6147, 38.9140),
            new City("厦门市", "福建省", 118.0894, 24.4798),
            new City("福州市", "福建省", 119.2965, 26.0745),
            new City("太原市", "山西省", 112.5489, 37.8706),
            new City("兰州市", "甘肃省", 103.8343, 36.0611),
            new City("乌鲁木齐市", "新疆维吾尔自治区", 87.6168, 43.8256),
            new City("拉萨市", "西藏自治区", 91.1409, 29.6456),
            new City("贵阳市", "贵州省", 106.6302, 26.6477),
            new City("香港", "", 114.1710, 22.3196),
            new City("澳门", "", 113.5439, 22.1987));

    private final JdbcTemplate jdbc;

    public MesDashboardRegionService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * Same task/date scope as the production dashboard; each surviving work order contributes once.
     * Every joined/subquery table has an explicit tenant and logical-delete constraint.
     */
    public Map<String, Object> distribution(Long workshopId, Long workstationId, LocalDate date) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        LocalDate selectedDate = date == null ? LocalDate.now() : date;
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId); // client join: a foreign-tenant client becomes an unlocated order
        arguments.add(tenantId);
        arguments.add(tenantId);
        arguments.add(selectedDate.plusDays(1).atStartOfDay());
        arguments.add(selectedDate.atStartOfDay());
        StringBuilder sql = new StringBuilder("""
                SELECT w.id, c.address
                FROM mes_pro_work_order w
                LEFT JOIN mes_md_client c ON c.id=w.client_id AND c.tenant_id=? AND c.deleted=0
                WHERE w.tenant_id=? AND w.deleted=0
                  AND EXISTS (SELECT 1 FROM mes_pro_task t
                              WHERE t.work_order_id=w.id AND t.tenant_id=? AND t.deleted=0
                                AND t.status<>5 AND t.start_time<?
                                AND COALESCE(t.end_time,t.start_time)>=?
                """);
        if (workstationId != null) {
            sql.append(" AND t.workstation_id=?");
            arguments.add(workstationId);
        }
        if (workshopId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM mes_md_workstation ws WHERE ws.id=t.workstation_id"
                    + " AND ws.tenant_id=? AND ws.deleted=0 AND ws.workshop_id=?)");
            arguments.add(tenantId);
            arguments.add(workshopId);
        }
        sql.append(")");

        // The outer work-order query plus EXISTS (rather than a task join) guarantees distinct orders.
        List<City> locations = jdbc.query(sql.toString(), (rs, rowNumber) -> locate(rs.getString("address")),
                arguments.toArray());
        Map<City, Long> counts = new HashMap<>();
        long locatedOrderCount = 0;
        for (City city : locations) {
            if (city != null) {
                counts.merge(city, 1L, Long::sum);
                locatedOrderCount++;
            }
        }
        List<Map<String, Object>> cities = counts.entrySet().stream()
                .sorted(Map.Entry.<City, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().name()))
                .map(entry -> {
                    City city = entry.getKey();
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("name", city.name());
                    point.put("value", entry.getValue());
                    point.put("coordinate", List.of(city.longitude(), city.latitude()));
                    return point;
                }).toList();
        long totalOrderCount = locations.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cities", cities);
        result.put("totalOrderCount", totalOrderCount);
        result.put("locatedOrderCount", locatedOrderCount);
        result.put("unlocatedOrderCount", totalOrderCount - locatedOrderCount);
        result.put("basis", BASIS);
        return result;
    }

    private static City locate(String address) {
        if (address == null || address.isBlank() || AMBIGUOUS_ADDRESS.matcher(address).find()) {
            return null;
        }
        String normalized = address.strip();
        City match = null;
        int position = -1;
        for (City city : CITIES) {
            int found = normalized.indexOf(city.standardName());
            if (found >= 0) {
                if (match != null || found != normalized.lastIndexOf(city.standardName())) {
                    return null;
                }
                match = city;
                position = found;
            }
        }
        if (match == null || !match.hasAdministrativePrefix(normalized.substring(0, position))) {
            return null;
        }
        String suffix = normalized.substring(position + match.standardName().length());
        if (STREET_SUFFIX.matcher(suffix).matches()) {
            return null; // e.g. 香港路/澳门路 is a street, not a city.
        }
        // Reject another unsupported city too. Common district/market words are not city names.
        String remainder = suffix.replaceAll("市辖区|市中区|市北区|市南区|市场|超市|市政", "");
        return remainder.contains("市") ? null : match;
    }

    private record City(String standardName, String province, double longitude, double latitude) {
        private String name() {
            return standardName.endsWith("市") ? standardName.substring(0, standardName.length() - 1) : standardName;
        }

        private boolean hasAdministrativePrefix(String prefix) {
            String compact = prefix.replaceAll("[\\s，,]", "");
            for (String country : List.of("中华人民共和国", "中国", "")) {
                if (compact.equals(country) || (!province.isEmpty() && compact.equals(country + province))) {
                    return true;
                }
            }
            return false;
        }
    }
}
