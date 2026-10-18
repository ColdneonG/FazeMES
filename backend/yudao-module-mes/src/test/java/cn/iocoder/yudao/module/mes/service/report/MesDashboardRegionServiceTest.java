package cn.iocoder.yudao.module.mes.service.report;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real service SQL against a standalone database, without a Spring/mock context. */
class MesDashboardRegionServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private JdbcTemplate jdbc;
    private MesDashboardRegionService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:dashboard_region_"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/dashboard_region.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        service = new MesDashboardRegionService(jdbc);
        TenantContextHolder.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        if (jdbc != null) {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void countsDistinctOrdersAndEnforcesEveryTenantAndDeletionBoundary() {
        client(11, 1, 0, "北京市海淀区私密地址88号");
        client(22, 2, 0, "上海市浦东新区另一个租户地址");
        client(33, 1, 1, "广东省广州市已删除客户");
        order(101, 1, 0, 11L);
        activeTask(1, 1, 0, 101, 10);
        activeTask(2, 1, 0, 101, 10); // The same order must count once.
        order(102, 1, 0, 22L); // A cross-tenant client reference must not disclose its location.
        activeTask(3, 1, 0, 102, 10);
        order(103, 1, 0, 33L);
        activeTask(4, 1, 0, 103, 10);
        order(104, 1, 0, null);
        activeTask(5, 1, 0, 104, 10);
        order(105, 1, 0, 999L);
        activeTask(6, 1, 0, 105, 10);
        order(106, 2, 0, 22L);
        activeTask(7, 2, 0, 106, 10);
        order(107, 1, 1, 11L);
        activeTask(8, 1, 0, 107, 10);
        order(108, 1, 0, 11L);
        activeTask(9, 2, 0, 108, 10); // A foreign task must not make a local order eligible.
        order(109, 1, 0, 11L);
        activeTask(10, 1, 1, 109, 10);
        order(110, 1, 0, 11L);
        task(11, 1, 0, 110, 10, 5, DAY.atTime(8, 0), DAY.atTime(9, 0));
        order(111, 1, 0, 11L);
        task(12, 1, 0, 111, 10, null, DAY.atTime(8, 0), DAY.atTime(9, 0));
        order(112, 1, 0, 11L); // No tasks.
        activeTask(13, 1, 0, 999, 10); // Orphan task.

        var response = service.distribution(null, null, DAY);
        assertCounts(response, 5, 1, 4);
        assertEquals(Map.of("北京", 1L), cityCounts(response));
        assertFalse(response.toString().contains("私密地址"));
        assertFalse(response.toString().contains("另一个租户"));

        TenantContextHolder.setIgnore(true);
        assertEquals(response, service.distribution(null, null, DAY));
        TenantContextHolder.setTenantId(2L);
        var otherTenant = service.distribution(null, null, DAY);
        assertCounts(otherTenant, 1, 1, 0);
        assertEquals(Map.of("上海", 1L), cityCounts(otherTenant));
    }

    @Test
    void usesDashboardDateOverlapAndIntersectsWorkshopAndWorkstationFilters() {
        client(1, 1, 0, "北京市朝阳区");
        station(11, 1, 0, 10);
        station(12, 1, 0, 20);
        station(13, 2, 0, 10);
        station(14, 1, 1, 10);
        for (long id = 101; id <= 112; id++) {
            order(id, 1, 0, 1L);
        }
        task(1, 1, 0, 101, 11, 1, DAY.minusDays(1).atTime(23, 30), DAY.atStartOfDay());
        task(2, 1, 0, 102, 11, 1, DAY.atTime(23, 59), DAY.plusDays(1).atTime(0, 1));
        task(3, 1, 0, 103, 12, 1, DAY.atTime(8, 0), null);
        task(4, 1, 0, 104, 11, 1, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(1).atTime(9, 0));
        task(5, 1, 0, 105, 11, 1, DAY.minusDays(1).atTime(22, 0), DAY.minusDays(1).atTime(23, 59));
        task(6, 1, 0, 106, 11, 1, DAY.minusDays(1).atTime(23, 0), null);
        activeTask(7, 1, 0, 107, 13);
        activeTask(8, 1, 0, 108, 14);
        task(9, 1, 0, 109, 11, 1, null, DAY.atTime(9, 0));
        task(10, 1, 0, 110, 11, 5, DAY.atTime(8, 0), DAY.atTime(9, 0));
        activeTask(11, 1, 1, 111, 11);
        activeTask(12, 1, 0, 112, 11);
        activeTask(13, 1, 0, 112, 12);

        assertCounts(service.distribution(null, null, DAY), 6, 6, 0);
        assertCounts(service.distribution(10L, null, DAY), 3, 3, 0);
        assertCounts(service.distribution(null, 11L, DAY), 3, 3, 0);
        assertCounts(service.distribution(null, 12L, DAY), 2, 2, 0);
        assertCounts(service.distribution(20L, 12L, DAY), 2, 2, 0);
        assertCounts(service.distribution(20L, 11L, DAY), 0, 0, 0);
        assertCounts(service.distribution(10L, 12L, DAY), 0, 0, 0);
        assertCounts(service.distribution(10L, 13L, DAY), 0, 0, 0);
        assertCounts(service.distribution(10L, 14L, DAY), 0, 0, 0);
    }

    @Test
    void missingUnknownStreetAndAmbiguousAddressesStayUnlocated() {
        String[] addresses = {null, "", "  ", "浙江省工业园", "杭州工业园", "上海路88号", "香港路88号", "澳门路1号",
                "河北省石家庄市上海路1号", "北京市朝阳区/上海市浦东新区", "北京市或天津市", "广东省广州市深圳市",
                "北京市朝阳区、温州市鹿城区", "上海市路1号", "江西省北京市朝阳区", "北京市地址待确认",
                "湖北省武汉市香港路9号", "拉萨工业园", "乌鲁木齐开发区"};
        for (int index = 0; index < addresses.length; index++) {
            long id = index + 1L;
            client(id, 1, 0, addresses[index]);
            order(id, 1, 0, id);
            activeTask(id, 1, 0, id, 1);
        }

        var response = service.distribution(null, null, DAY);
        assertCounts(response, addresses.length, 0, addresses.length);
        assertTrue(cities(response).isEmpty());
    }

    @Test
    void returnsRealCityCountsCenterCoordinatesAndOnlyThePublicAggregateContract() {
        client(1, 1, 0, "中国，北京市海淀区SECRET88号");
        client(2, 1, 0, "北京市朝阳区SECRET99号");
        client(3, 1, 0, "广东省深圳市南山区SECRET1号");
        client(4, 1, 0, "中华人民共和国广东省深圳市福田区SECRET2号");
        client(5, 1, 0, "中国香港特别行政区中西区SECRET3号");
        client(6, 1, 0, "澳门特别行政区花地玛堂区SECRET4号");
        client(7, 1, 0, "山东省青岛市市北区市场路SECRET5号");
        for (long id = 1; id <= 7; id++) {
            order(id, 1, 0, id);
            activeTask(id, 1, 0, id, 1);
        }
        List<Map<String, Object>> clientRowsBefore = jdbc.queryForList("SELECT * FROM mes_md_client ORDER BY id");
        List<Map<String, Object>> orderRowsBefore = jdbc.queryForList("SELECT * FROM mes_pro_work_order ORDER BY id");
        List<Map<String, Object>> taskRowsBefore = jdbc.queryForList("SELECT * FROM mes_pro_task ORDER BY id");

        var response = service.distribution(null, null, DAY);
        assertCounts(response, 7, 7, 0);
        assertEquals(Map.of("北京", 2L, "深圳", 2L, "香港", 1L, "澳门", 1L, "青岛", 1L), cityCounts(response));
        assertEquals(Set.of("cities", "totalOrderCount", "locatedOrderCount", "unlocatedOrderCount", "basis"), response.keySet());
        for (Map<String, Object> point : cities(response)) {
            assertEquals(Set.of("name", "value", "coordinate"), point.keySet());
        }
        assertEquals(List.of(116.4074, 39.9042), city(response, "北京").get("coordinate"));
        assertEquals(List.of(114.0579, 22.5431), city(response, "深圳").get("coordinate"));
        assertEquals(List.of(114.1710, 22.3196), city(response, "香港").get("coordinate"));
        assertEquals(List.of(113.5439, 22.1987), city(response, "澳门").get("coordinate"));
        assertEquals(2L, cities(response).get(0).get("value"));
        assertEquals(2L, cities(response).get(1).get("value"));
        assertFalse(response.toString().contains("SECRET"));
        assertFalse(response.toString().contains("保密客户"));
        assertTrue(response.get("basis").toString().contains("城市中心示意"));
        assertEquals(clientRowsBefore, jdbc.queryForList("SELECT * FROM mes_md_client ORDER BY id"));
        assertEquals(orderRowsBefore, jdbc.queryForList("SELECT * FROM mes_pro_work_order ORDER BY id"));
        assertEquals(taskRowsBefore, jdbc.queryForList("SELECT * FROM mes_pro_task ORDER BY id"));
    }

    @Test
    void recognizesAllSupportedStandardCitiesWithoutInventingOtherLocations() {
        List<String> standardNames = List.of("北京市", "上海市", "广州市", "深圳市", "杭州市", "南京市", "成都市", "重庆市",
                "武汉市", "西安市", "天津市", "苏州市", "青岛市", "沈阳市", "长沙市", "大连市", "厦门市", "福州市",
                "太原市", "兰州市", "乌鲁木齐市", "拉萨市", "贵阳市", "香港", "澳门");
        for (int index = 0; index < standardNames.size(); index++) {
            long id = index + 1L;
            client(id, 1, 0, standardNames.get(index) + "工业园区88号");
            order(id, 1, 0, id);
            activeTask(id, 1, 0, id, 1);
        }
        var response = service.distribution(null, null, DAY);
        assertCounts(response, 25, 25, 0);
        assertEquals(Set.of("北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆", "武汉", "西安", "天津", "苏州",
                "青岛", "沈阳", "长沙", "大连", "厦门", "福州", "太原", "兰州", "乌鲁木齐", "拉萨", "贵阳", "香港", "澳门"),
                cityCounts(response).keySet());
        assertTrue(cityCounts(response).values().stream().allMatch(value -> value == 1L));
    }

    @Test
    void defaultsToTodayAndKeepsAnEmptyScopeEmpty() {
        var empty = service.distribution(null, null, null);
        assertCounts(empty, 0, 0, 0);
        assertTrue(cities(empty).isEmpty());
        client(1, 1, 0, "上海市浦东新区");
        order(1, 1, 0, 1L);
        task(1, 1, 0, 1, 1, 1, LocalDate.now().atTime(8, 0), LocalDate.now().atTime(9, 0));
        assertEquals(service.distribution(null, null, LocalDate.now()), service.distribution(null, null, null));
        assertCounts(service.distribution(null, null, null), 1, 1, 0);
    }

    @Test
    void missingTenantFailsClosedBeforeAnyDatabaseAccess() {
        jdbc.execute("DROP ALL OBJECTS");
        TenantContextHolder.clear();
        assertThrows(NullPointerException.class, () -> service.distribution(null, null, DAY));
    }

    private void client(long id, long tenant, int deleted, String address) {
        jdbc.update("INSERT INTO mes_md_client (id,tenant_id,deleted,name,address) VALUES (?,?,?,?,?)",
                id, tenant, deleted, "保密客户" + id, address);
    }

    private void order(long id, long tenant, int deleted, Long clientId) {
        jdbc.update("INSERT INTO mes_pro_work_order (id,tenant_id,deleted,client_id) VALUES (?,?,?,?)",
                id, tenant, deleted, clientId);
    }

    private void station(long id, long tenant, int deleted, long workshop) {
        jdbc.update("INSERT INTO mes_md_workstation (id,tenant_id,deleted,workshop_id) VALUES (?,?,?,?)",
                id, tenant, deleted, workshop);
    }

    private void activeTask(long id, long tenant, int deleted, long order, long station) {
        task(id, tenant, deleted, order, station, 1, DAY.atTime(8, 0), DAY.atTime(9, 0));
    }

    private void task(long id, long tenant, int deleted, long order, long station, Integer status,
                      LocalDateTime start, LocalDateTime end) {
        jdbc.update("INSERT INTO mes_pro_task (id,tenant_id,deleted,work_order_id,workstation_id,status,start_time,end_time)"
                + " VALUES (?,?,?,?,?,?,?,?)", id, tenant, deleted, order, station, status, start, end);
    }

    private static void assertCounts(Map<String, Object> result, long total, long located, long unlocated) {
        assertEquals(total, result.get("totalOrderCount"));
        assertEquals(located, result.get("locatedOrderCount"));
        assertEquals(unlocated, result.get("unlocatedOrderCount"));
        assertEquals(total, located + unlocated);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cities(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("cities");
    }

    private static Map<String, Long> cityCounts(Map<String, Object> result) {
        return cities(result).stream().collect(Collectors.toMap(point -> (String) point.get("name"),
                point -> ((Number) point.get("value")).longValue()));
    }

    private static Map<String, Object> city(Map<String, Object> result, String name) {
        return cities(result).stream().filter(point -> name.equals(point.get("name"))).findFirst().orElseThrow();
    }
}
