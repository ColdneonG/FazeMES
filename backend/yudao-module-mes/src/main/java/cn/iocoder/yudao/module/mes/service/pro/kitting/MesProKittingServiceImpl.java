package cn.iocoder.yudao.module.mes.service.pro.kitting;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.kitting.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.kitting.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.kitting.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.mes.enums.pro.MesProKittingErrors.*;

/** Reservations never change on-hand balances. Only the existing stock transaction does. */
@Service
@Validated
public class MesProKittingServiceImpl implements MesProKittingService {
    @Resource private MesProKittingMapper mapper;
    @Resource private MesProWorkOrderMapper workOrderMapper;
    @Resource private MesProShortageFollowupMapper followupMapper;
    @Resource private MesProReservationEventMapper eventMapper;

    private Long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    private static BigDecimal n(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal positive(BigDecimal value) { return n(value).max(BigDecimal.ZERO); }
    private MesProWorkOrderDO order(Long id, boolean lock) {
        var order = lock ? workOrderMapper.selectByIdForUpdate(id) : workOrderMapper.selectById(id);
        if (order == null) throw exception(ORDER_NOT_FOUND);
        return order;
    }
    private MesProWorkOrderDO openOrder(Long id) {
        var order = order(id, true);
        if (!Objects.equals(order.getStatus(), 1)) throw exception(ORDER_NOT_OPEN);
        return order;
    }

    @Override
    public KittingAnalysisVO analyze(Long workOrderId) {
        var order = order(workOrderId, false);
        var rows = mapper.selectRequirements(workOrderId, tenant());
        var result = new KittingAnalysisVO().setWorkOrderId(order.getId()).setWorkOrderCode(order.getCode())
                .setWorkOrderName(order.getName()).setWorkOrderStatus(order.getStatus())
                .setAnalyzedAt(LocalDateTime.now()).setMaterials(rows);
        if (rows.isEmpty()) return result.setReady(false).setStatus("NO_BOM");
        List<Long> itemIds = rows.stream().map(KittingMaterialVO::getItemId).toList();
        var stocks = mapper.selectCandidates(itemIds, tenant());
        var active = mapper.selectActive(itemIds, tenant());
        var tracking = followupMapper.selectList(MesProShortageFollowupDO::getWorkOrderId, workOrderId)
                .stream().collect(Collectors.toMap(MesProShortageFollowupDO::getItemId, x -> x));
        for (var row : rows) {
            var itemStocks = stocks.stream().filter(s -> Objects.equals(s.getItemId(), row.getItemId()) && s.isEligible()).toList();
            BigDecimal physical = BigDecimal.ZERO, free = BigDecimal.ZERO, usableOwn = BigDecimal.ZERO, other = BigDecimal.ZERO;
            for (var stock : itemStocks) {
                var reservations = active.stream().filter(r -> Objects.equals(r.getMaterialStockId(), stock.getId())).toList();
                BigDecimal total = reservations.stream().map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal own = reservations.stream().filter(r -> Objects.equals(r.getWorkOrderId(), workOrderId))
                        .map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
                physical = physical.add(n(stock.getQuantity()));
                free = free.add(positive(stock.getQuantity().subtract(total)));
                usableOwn = usableOwn.add(own.min(n(stock.getQuantity())));
                other = other.add(total.subtract(own));
            }
            BigDecimal own = active.stream().filter(r -> Objects.equals(r.getWorkOrderId(), workOrderId)
                            && Objects.equals(r.getItemId(), row.getItemId()))
                    .map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal issued = positive(mapper.selectNetIssued(workOrderId, row.getItemId(), tenant()));
            BigDecimal outstanding = positive(row.getRequiredQuantity().subtract(issued));
            BigDecimal available = free.add(usableOwn);
            row.setIssuedQuantity(issued).setOutstandingQuantity(outstanding).setPhysicalQuantity(physical)
                    .setReservedQuantity(own).setUsableReservedQuantity(usableOwn).setOtherReservedQuantity(other)
                    .setFreeQuantity(free).setAvailableQuantity(available).setShortageQuantity(positive(outstanding.subtract(available)));
            var followup = tracking.get(row.getItemId());
            row.setTrackingStatus(followup == null ? "OPEN" : followup.getTrackingStatus())
                    .setExpectedDate(followup == null ? null : followup.getExpectedDate())
                    .setRemark(followup == null ? null : followup.getRemark());
        }
        int shortages = (int) rows.stream().filter(r -> r.getShortageQuantity().signum() > 0).count();
        result.setShortageItemCount(shortages).setReady(shortages == 0);
        boolean issued = rows.stream().allMatch(r -> r.getOutstandingQuantity().signum() == 0);
        return result.setStatus(!Objects.equals(order.getStatus(), 1) ? "CLOSED" : issued ? "ISSUED" : shortages == 0 ? "READY" : "SHORTAGE");
    }

    @Override
    public List<KittingAnalysisVO> board(String keyword, boolean onlyShortage) {
        return mapper.selectOpenOrders(keyword, tenant()).stream().map(this::analyze)
                .filter(row -> !onlyShortage || row.getShortageItemCount() > 0).toList();
    }

    /** All area locks precede stock/reservation locks; candidates are re-read after waiting. */
    private List<KittingStockRow> lockStocks(List<KittingStockRow> candidates, List<MesProMaterialReservationDO> owned) {
        Map<Long, KittingStockRow> snapshots = new LinkedHashMap<>();
        candidates.forEach(s -> snapshots.put(s.getId(), s));
        for (var reservation : owned) {
            if (n(reservation.getQuantity()).signum() <= 0 || snapshots.containsKey(reservation.getMaterialStockId())) continue;
            var stock = mapper.selectStock(reservation.getMaterialStockId(), tenant());
            if (stock != null) snapshots.put(stock.getId(), stock);
        }
        snapshots.values().stream().map(KittingStockRow::getAreaId).distinct().sorted().forEach(id -> mapper.lockArea(id, tenant()));
        return snapshots.keySet().stream().sorted().map(id -> mapper.lockStock(id, tenant())).filter(Objects::nonNull)
                .sorted(Comparator.comparing(KittingStockRow::getReceiptTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(KittingStockRow::getId)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public KittingAnalysisVO reserve(Long workOrderId) {
        openOrder(workOrderId);
        var requirements = mapper.selectRequirements(workOrderId, tenant());
        if (requirements.isEmpty() || requirements.stream().anyMatch(r -> n(r.getRequiredQuantity()).signum() <= 0)) throw exception(NO_BOM);
        var owned = mapper.selectForOrder(workOrderId, tenant());
        var stocks = lockStocks(mapper.selectCandidates(requirements.stream().map(KittingMaterialVO::getItemId).toList(), tenant()), owned);
        Map<Long, List<MesProMaterialReservationDO>> reservations = new HashMap<>();
        for (var stock : stocks) reservations.put(stock.getId(), new ArrayList<>(mapper.lockForStock(stock.getId(), tenant())));
        for (var existing : owned) {
            if (n(existing.getQuantity()).signum() <= 0) continue;
            var stock = stocks.stream().filter(s -> Objects.equals(s.getId(), existing.getMaterialStockId())).findFirst().orElse(null);
            if (stock == null || !stock.isEligible()) throw exception(INVALID_RESERVATION);
        }
        for (var item : requirements) {
            BigDecimal issued = positive(mapper.selectNetIssued(workOrderId, item.getItemId(), tenant()));
            BigDecimal outstanding = positive(item.getRequiredQuantity().subtract(issued));
            var own = reservations.values().stream().flatMap(Collection::stream)
                    .filter(r -> Objects.equals(r.getWorkOrderId(), workOrderId) && Objects.equals(r.getItemId(), item.getItemId())).toList();
            BigDecimal ownQuantity = own.stream().map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = positive(outstanding.subtract(ownQuantity));
            for (var stock : stocks) {
                if (remaining.signum() == 0) break;
                if (!stock.isEligible() || !Objects.equals(stock.getItemId(), item.getItemId())) continue;
                var stockReservations = reservations.get(stock.getId());
                BigDecimal total = stockReservations.stream().map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal take = positive(stock.getQuantity().subtract(total)).min(remaining);
                if (take.signum() == 0) continue;
                var reservation = stockReservations.stream().filter(r -> Objects.equals(r.getWorkOrderId(), workOrderId)).findFirst().orElse(null);
                if (reservation == null) {
                    reservation = new MesProMaterialReservationDO().setWorkOrderId(workOrderId).setItemId(item.getItemId())
                            .setMaterialStockId(stock.getId()).setQuantity(take).setAllocatedQuantity(take)
                            .setConsumedQuantity(BigDecimal.ZERO).setReleasedQuantity(BigDecimal.ZERO);
                    mapper.insert(reservation);
                    stockReservations.add(reservation);
                } else {
                    reservation.setQuantity(n(reservation.getQuantity()).add(take))
                            .setAllocatedQuantity(n(reservation.getAllocatedQuantity()).add(take));
                    mapper.updateById(reservation);
                }
                event(reservation, "RESERVE", take, null);
                remaining = remaining.subtract(take);
            }
        }
        return analyze(workOrderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long workOrderId) { openOrder(workOrderId); releaseLocked(workOrderId, false); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseForClosedOrder(Long workOrderId) { order(workOrderId, true); releaseLocked(workOrderId, false); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseExcessAfterIssue(Long workOrderId) { order(workOrderId, true); releaseLocked(workOrderId, true); }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void lockForIssue(Long workOrderId, List<Long> issueAreaIds) {
        // The enclosing issue uses READ_COMMITTED so reservations committed while
        // waiting for the order lock are included before any area/stock is locked.
        order(workOrderId, true);
        SortedSet<Long> areas = new TreeSet<>();
        issueAreaIds.stream().filter(Objects::nonNull).forEach(areas::add);
        for (var owned : mapper.selectForOrder(workOrderId, tenant())) {
            if (n(owned.getQuantity()).signum() <= 0) continue;
            var stock = mapper.selectStock(owned.getMaterialStockId(), tenant());
            if (stock == null || stock.getAreaId() == null) throw exception(INVALID_RESERVATION);
            areas.add(stock.getAreaId());
        }
        areas.forEach(area -> mapper.lockArea(area, tenant()));
    }

    private void releaseLocked(Long workOrderId, boolean excessOnly) {
        var owned = mapper.selectForOrder(workOrderId, tenant());
        var stocks = lockStocks(List.of(), owned);
        Map<Long, BigDecimal> allowed = new HashMap<>();
        if (excessOnly) {
            for (var row : mapper.selectRequirements(workOrderId, tenant())) {
                allowed.put(row.getItemId(), positive(row.getRequiredQuantity()
                        .subtract(positive(mapper.selectNetIssued(workOrderId, row.getItemId(), tenant())))));
            }
        }
        for (var stock : stocks) {
            for (var row : mapper.lockForStock(stock.getId(), tenant())) {
                if (!Objects.equals(row.getWorkOrderId(), workOrderId) || n(row.getQuantity()).signum() <= 0) continue;
                BigDecimal keep = excessOnly ? n(allowed.get(row.getItemId())).min(row.getQuantity()) : BigDecimal.ZERO;
                BigDecimal release = row.getQuantity().subtract(keep);
                if (excessOnly) allowed.put(row.getItemId(), positive(n(allowed.get(row.getItemId())).subtract(keep)));
                if (release.signum() <= 0) continue;
                row.setQuantity(keep).setReleasedQuantity(n(row.getReleasedQuantity()).add(release));
                mapper.updateById(row);
                event(row, excessOnly ? "RELEASE_EXCESS" : "RELEASE", release.negate(), null);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateAndConsumeOutbound(Long stockId, BigDecimal quantity, Long issueWorkOrderId, Long issueId) {
        // Caller has the area's lock. The current read sees reservations committed while it waited.
        var stock = mapper.lockStock(stockId, tenant());
        if (stock == null || !stock.isEligible()) throw exception(INVALID_RESERVATION);
        var rows = mapper.lockForStock(stockId, tenant());
        BigDecimal others = rows.stream().filter(r -> !Objects.equals(r.getWorkOrderId(), issueWorkOrderId))
                .map(r -> n(r.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stock.getQuantity().subtract(others).compareTo(quantity) < 0) throw exception(STOCK_RESERVED);
        if (issueWorkOrderId == null) return;
        BigDecimal remaining = quantity;
        for (var row : rows) {
            if (!Objects.equals(row.getWorkOrderId(), issueWorkOrderId) || remaining.signum() <= 0) continue;
            BigDecimal consume = n(row.getQuantity()).min(remaining);
            if (consume.signum() <= 0) continue;
            row.setQuantity(row.getQuantity().subtract(consume)).setConsumedQuantity(n(row.getConsumedQuantity()).add(consume));
            mapper.updateById(row);
            event(row, "ISSUE", consume.negate(), issueId);
            remaining = remaining.subtract(consume);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveFollowup(KittingFollowupReqVO request) {
        openOrder(request.getWorkOrderId());
        if (!Set.of("OPEN", "ORDERED", "ARRIVED").contains(request.getTrackingStatus())) throw exception(INVALID_TRACKING);
        if (mapper.selectRequirements(request.getWorkOrderId(), tenant()).stream()
                .noneMatch(r -> Objects.equals(r.getItemId(), request.getItemId()))) throw exception(INVALID_MATERIAL);
        var existing = followupMapper.selectOne(new LambdaQueryWrapperX<MesProShortageFollowupDO>()
                .eq(MesProShortageFollowupDO::getWorkOrderId, request.getWorkOrderId())
                .eq(MesProShortageFollowupDO::getItemId, request.getItemId()));
        var row = new MesProShortageFollowupDO().setWorkOrderId(request.getWorkOrderId()).setItemId(request.getItemId())
                .setExpectedDate(request.getExpectedDate()).setTrackingStatus(request.getTrackingStatus()).setRemark(request.getRemark());
        if (existing == null) followupMapper.insert(row);
        else {
            // Explicitly permit clearing the expected date and note (MyBatis NOT_NULL updates would retain them).
            var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesProShortageFollowupDO>()
                    .eq(MesProShortageFollowupDO::getId, existing.getId()).set(MesProShortageFollowupDO::getExpectedDate, request.getExpectedDate())
                    .set(MesProShortageFollowupDO::getTrackingStatus, request.getTrackingStatus()).set(MesProShortageFollowupDO::getRemark, request.getRemark());
            followupMapper.update(null, update);
        }
    }

    @Override
    public List<Map<String, Object>> reservations(Long workOrderId) {
        order(workOrderId, false);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : mapper.selectForOrder(workOrderId, tenant())) {
            var stock = mapper.selectStock(row.getMaterialStockId(), tenant());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", row.getId()); data.put("materialStockId", row.getMaterialStockId()); data.put("itemId", row.getItemId());
            data.put("quantity", row.getQuantity()); data.put("allocatedQuantity", row.getAllocatedQuantity());
            data.put("consumedQuantity", row.getConsumedQuantity()); data.put("releasedQuantity", row.getReleasedQuantity());
            data.put("batchCode", stock == null ? null : stock.getBatchCode()); data.put("warehouseName", stock == null ? null : stock.getWarehouseName());
            data.put("locationName", stock == null ? null : stock.getLocationName()); data.put("areaName", stock == null ? null : stock.getAreaName());
            data.put("eligible", stock != null && stock.isEligible()); result.add(data);
        }
        return result;
    }

    @Override
    public List<MesProReservationEventDO> history(Long workOrderId) {
        order(workOrderId, false);
        return eventMapper.selectList(new LambdaQueryWrapperX<MesProReservationEventDO>()
                .eq(MesProReservationEventDO::getWorkOrderId, workOrderId).orderByDesc(MesProReservationEventDO::getId).last("LIMIT 200"));
    }

    private void event(MesProMaterialReservationDO row, String action, BigDecimal change, Long referenceId) {
        eventMapper.insert(new MesProReservationEventDO().setWorkOrderId(row.getWorkOrderId()).setItemId(row.getItemId())
                .setMaterialStockId(row.getMaterialStockId()).setReservationId(row.getId()).setAction(action)
                .setQuantity(change).setReferenceId(referenceId).setOperatorId(getLoginUserId()));
    }
}
