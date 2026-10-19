package cn.iocoder.yudao.module.mes.service.insight;

import java.time.*;
import java.util.*;

/** Deterministic finite-capacity scenario model: one resource per station, sequential order operations. */
public final class MesScheduleEngine {
    private MesScheduleEngine() {}
    public record Job(long id, long orderId, long stationId, String name, LocalDateTime plannedStart,
                      LocalDateTime due, double minutes, int sequence) {}
    public record Slot(Job job, LocalDateTime start, LocalDateTime end, double overtimeHours) {}
    public record Plan(List<Slot> slots, Map<Long, LocalDateTime> finishes, double overtimeHours) {}

    public static LocalDateTime nextWorkingTime(LocalDateTime time, int hours) {
        LocalDateTime cursor = time.withSecond(0).withNano(0);
        if (time.getSecond() != 0 || time.getNano() != 0) cursor = cursor.plusMinutes(1);
        for (int i = 0; i < 8; i++) {
            boolean weekend = cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (weekend || !cursor.toLocalTime().isBefore(LocalTime.of(8 + hours, 0))) {
                cursor = cursor.toLocalDate().plusDays(1).atTime(8, 0);
            } else if (cursor.getHour() < 8) return cursor.toLocalDate().atTime(8, 0);
            else return cursor;
        }
        throw new IllegalArgumentException("工作日历超出范围");
    }

    public static LocalDateTime advance(LocalDateTime start, double minutes, int hours) {
        if (hours < 8 || hours > 12 || !Double.isFinite(minutes) || minutes <= 0 || minutes > 1_000_000)
            throw new IllegalArgumentException("工时或日工作时长无效");
        long remaining = (long)Math.ceil(minutes);
        LocalDateTime cursor = nextWorkingTime(start, hours);
        while (remaining > 0) {
            long available = Duration.between(cursor, cursor.toLocalDate().atTime(8 + hours, 0)).toMinutes();
            long use = Math.min(remaining, available);
            cursor = cursor.plusMinutes(use);
            remaining -= use;
            if (remaining > 0) cursor = nextWorkingTime(cursor, hours);
        }
        return cursor;
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private static double overtime(LocalDateTime start, LocalDateTime end) {
        long minutes = 0;
        for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            LocalDateTime from = max(start, day.atTime(16, 0));
            LocalDateTime until = end.isBefore(day.atTime(20, 0)) ? end : day.atTime(20, 0);
            if (until.isAfter(from)) minutes += Duration.between(from, until).toMinutes();
        }
        return minutes / 60.0;
    }

    public static Plan schedule(List<Job> jobs, LocalDateTime now, int hours, Long priorityOrder) {
        if (jobs.size() > 1500) throw new IllegalArgumentException("单次仿真最多支持1500个任务");
        Map<Long, Deque<Job>> queues = new TreeMap<>();
        jobs.stream().sorted(Comparator.comparingInt(Job::sequence)
                .thenComparing(Job::plannedStart, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingLong(Job::id))
                .forEach(job -> queues.computeIfAbsent(job.orderId(), k -> new ArrayDeque<>()).add(job));
        Map<Long, LocalDateTime> resources = new HashMap<>(), finishes = new HashMap<>();
        List<Slot> slots = new ArrayList<>();
        while (!queues.isEmpty()) {
            List<Job> heads = queues.values().stream().map(Deque::getFirst).toList();
            Comparator<Job> comparator = Comparator.comparingInt(j -> Objects.equals(priorityOrder, j.orderId()) ? 0 : 1);
            comparator = comparator.thenComparing(j -> j.plannedStart() == null ? now : j.plannedStart()).thenComparingLong(Job::id);
            Job job = heads.stream().min(comparator).orElseThrow();
            LocalDateTime ready = Objects.equals(priorityOrder, job.orderId()) || job.plannedStart() == null
                    ? now : max(now, job.plannedStart());
            ready = max(ready, finishes.getOrDefault(job.orderId(), now));
            ready = max(ready, resources.getOrDefault(job.stationId(), now));
            LocalDateTime start = nextWorkingTime(ready, hours), end = advance(start, job.minutes(), hours);
            slots.add(new Slot(job, start, end, overtime(start, end)));
            finishes.put(job.orderId(), end);
            resources.put(job.stationId(), end);
            Deque<Job> queue = queues.get(job.orderId());
            queue.removeFirst();
            if (queue.isEmpty()) queues.remove(job.orderId());
        }
        return new Plan(slots, finishes, slots.stream().mapToDouble(Slot::overtimeHours).sum());
    }
}
