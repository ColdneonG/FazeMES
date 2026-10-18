package cn.iocoder.yudao.module.mes.service.pro.sntrace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.mes.controller.admin.pro.sntrace.vo.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.feedback.MesProFeedbackDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.route.MesProRouteProcessDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.sntrace.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.task.MesProTaskDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.pro.workorder.MesProWorkOrderDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.itemconsume.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.materialstock.MesWmMaterialStockDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.sn.MesWmSnDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.transaction.MesWmTransactionDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.warehouse.MesWmWarehouseDO;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.feedback.MesProFeedbackMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.route.MesProRouteProcessMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.sntrace.*;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.task.MesProTaskMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.pro.workorder.MesProWorkOrderMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.itemconsume.*;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.materialstock.MesWmMaterialStockMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.sn.MesWmSnMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.transaction.MesWmTransactionMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.warehouse.MesWmWarehouseMapper;
import cn.iocoder.yudao.module.mes.enums.MesBizTypeConstants;
import cn.iocoder.yudao.module.mes.enums.pro.MesProFeedbackStatusEnum;
import cn.iocoder.yudao.module.mes.enums.wm.*;
import cn.iocoder.yudao.module.mes.service.wm.barcode.MesWmBarcodeService;
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
import static cn.iocoder.yudao.module.mes.service.pro.sntrace.SnTraceErrorConstants.*;

@Service
@Validated
public class MesProSnTraceServiceImpl implements MesProSnTraceService {
    @Resource private MesProSnTraceMapper traceMapper;
    @Resource private MesProSnStationMapper stationMapper;
    @Resource private MesProSnMaterialMapper materialMapper;
    @Resource private MesWmSnMapper snMapper;
    @Resource private MesProWorkOrderMapper workOrderMapper;
    @Resource private MesProFeedbackMapper feedbackMapper;
    @Resource private MesProTaskMapper taskMapper;
    @Resource private MesProRouteProcessMapper routeProcessMapper;
    @Resource private MesWmItemConsumeMapper consumeMapper;
    @Resource private MesWmItemConsumeLineMapper lineMapper;
    @Resource private MesWmItemConsumeDetailMapper detailMapper;
    @Resource private MesWmMaterialStockMapper stockMapper;
    @Resource private MesWmTransactionMapper transactionMapper;
    @Resource private MesWmWarehouseMapper warehouseMapper;
    @Resource private MesWmBarcodeService barcodeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(MesProSnTraceRegisterReqVO request) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        MesProWorkOrderDO workOrder = workOrderMapper.selectByIdForUpdate(request.getWorkOrderId());
        if (workOrder == null) throw exception(NOT_FOUND);
        if (workOrder.getProductId() == null) {
            throw exception(IDENTITY_INVALID);
        }
        String code = StrUtil.trim(request.getSnCode());
        if (request.getSnId() == null && StrUtil.isBlank(code)) throw exception(SN_REQUIRED);
        LambdaQueryWrapperX<MesWmSnDO> snQuery = new LambdaQueryWrapperX<>();
        if (request.getSnId() != null) snQuery.eq(MesWmSnDO::getId, request.getSnId());
        else snQuery.eq(MesWmSnDO::getCode, code);
        List<MesWmSnDO> matches = snMapper.selectList(snQuery.orderByAsc(MesWmSnDO::getId).last("FOR UPDATE"));
        if (matches.size() > 1 || (matches.isEmpty() && request.getSnId() != null)) throw exception(SN_REQUIRED);
        MesWmSnDO sn;
        if (matches.isEmpty()) {
            sn = new MesWmSnDO().setUuid(IdUtil.fastSimpleUUID()).setCode(code)
                    .setItemId(workOrder.getProductId()).setWorkOrderId(workOrder.getId())
                    .setBatchCode(workOrder.getBatchCode());
            snMapper.insert(sn);
            barcodeService.autoGenerateBarcode(BarcodeBizTypeEnum.SN.getValue(), sn.getId(), code, code);
        } else {
            sn = matches.get(0);
            if ((StrUtil.isNotBlank(code) && !code.equals(sn.getCode()))
                    || !Objects.equals(sn.getItemId(), workOrder.getProductId())
                    || (sn.getWorkOrderId() != null && !Objects.equals(sn.getWorkOrderId(), workOrder.getId()))
                    || (StrUtil.isNotBlank(sn.getBatchCode()) && !Objects.equals(sn.getBatchCode(), workOrder.getBatchCode()))) {
                throw exception(IDENTITY_INVALID);
            }
        }
        MesProSnTraceDO existing = traceMapper.selectOne(new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getTenantId, tenant).eq(MesProSnTraceDO::getSnId, sn.getId()));
        if (existing != null) {
            if (!Objects.equals(existing.getWorkOrderId(), workOrder.getId())
                    || !Objects.equals(existing.getProductId(), workOrder.getProductId())
                    || !Objects.equals(existing.getProductBatchCode(), workOrder.getBatchCode())) throw exception(IDENTITY_INVALID);
            return existing.getId();
        }
        snMapper.updateById(new MesWmSnDO().setId(sn.getId()).setWorkOrderId(workOrder.getId())
                .setBatchCode(workOrder.getBatchCode()));
        MesProSnTraceDO trace = new MesProSnTraceDO().setSnId(sn.getId()).setSnCode(sn.getCode())
                .setWorkOrderId(workOrder.getId()).setProductId(workOrder.getProductId())
                .setProductBatchCode(workOrder.getBatchCode()).setStatus("REGISTERED");
        trace.setTenantId(tenant);
        traceMapper.insert(trace);
        return trace.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Long station(MesProSnTraceStationReqVO request) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        Long operator = getLoginUserId();
        if (operator == null) throw exception(FEEDBACK_INVALID);
        // All allocations for a feedback serialize here; same-SN operations serialize on trace.
        // Never lock the work order after this point (feedback approval uses order -> feedback).
        MesProFeedbackDO feedback = feedbackMapper.selectByIdForUpdate(request.getFeedbackId());
        MesProSnTraceDO trace = traceMapper.selectForUpdate(request.getTraceId());
        if (trace == null || !Objects.equals(trace.getTenantId(), tenant)) throw exception(NOT_FOUND);
        String hash = requestHash(request);
        MesProSnStationDO previous = stationMapper.selectOne(new LambdaQueryWrapperX<MesProSnStationDO>()
                .eq(MesProSnStationDO::getTenantId, tenant).eq(MesProSnStationDO::getTraceId, trace.getId())
                .eq(MesProSnStationDO::getRequestKey, request.getRequestKey()));
        if (previous != null) {
            if (!hash.equals(previous.getRequestHash())) throw exception(REQUEST_CONFLICT);
            return previous.getId();
        }
        if ("COMPLETED".equals(trace.getStatus())) throw exception(STATION_INVALID);
        validateFeedback(feedback, trace);
        List<MesProSnStationDO> history = stations(trace.getId());
        List<MesProRouteProcessDO> route = routeProcessMapper.selectListByRouteId(feedback.getRouteId());
        MesProRouteProcessDO current = validateSequence(feedback, history, route);
        long count = stationMapper.selectCount(new LambdaQueryWrapperX<MesProSnStationDO>()
                .eq(MesProSnStationDO::getTenantId, tenant).eq(MesProSnStationDO::getFeedbackId, feedback.getId()));
        if (BigDecimal.valueOf(count + 1).compareTo(feedback.getFeedbackQuantity()) > 0) throw exception(FEEDBACK_FULL);
        Source source = source(feedback);
        List<MesProSnMaterialDO> allocated = materialMapper.selectList(new LambdaQueryWrapperX<MesProSnMaterialDO>()
                .eq(MesProSnMaterialDO::getTenantId, tenant).eq(MesProSnMaterialDO::getFeedbackId, feedback.getId()));
        validateShares(request.getMaterials(), source.lines, source.details, allocated, feedback.getFeedbackQuantity());

        MesProSnStationDO station = new MesProSnStationDO().setTraceId(trace.getId())
                .setRequestKey(request.getRequestKey()).setRequestHash(hash).setWorkOrderId(feedback.getWorkOrderId())
                .setFeedbackId(feedback.getId()).setFeedbackCode(feedback.getCode()).setTaskId(feedback.getTaskId())
                .setRouteId(feedback.getRouteId()).setProcessId(feedback.getProcessId()).setProcessSort(current.getSort())
                .setWorkstationId(feedback.getWorkstationId()).setOperatorId(operator).setFeedbackUserId(feedback.getFeedbackUserId())
                .setStationTime(LocalDateTime.now()).setFeedbackTime(feedback.getFeedbackTime());
        station.setTenantId(tenant);
        stationMapper.insert(station);
        Map<Long, MesWmItemConsumeDetailDO> byId = source.details.stream()
                .collect(Collectors.toMap(MesWmItemConsumeDetailDO::getId, d -> d));
        for (MesProSnTraceStationReqVO.Material input : request.getMaterials()) {
            MesWmItemConsumeDetailDO detail = byId.get(input.getConsumeDetailId());
            MesProSnMaterialDO material = new MesProSnMaterialDO().setTraceId(trace.getId()).setStationId(station.getId())
                    .setFeedbackId(feedback.getId()).setConsumeId(detail.getConsumeId()).setConsumeLineId(detail.getLineId())
                    .setConsumeDetailId(detail.getId()).setMaterialStockId(detail.getMaterialStockId()).setItemId(detail.getItemId())
                    .setBatchId(detail.getBatchId()).setBatchCode(detail.getBatchCode()).setQuantity(input.getQuantity());
            material.setTenantId(tenant);
            materialMapper.insert(material);
        }
        if (route.stream().allMatch(p -> Objects.equals(p.getProcessId(), feedback.getProcessId())
                || history.stream().anyMatch(h -> Objects.equals(h.getProcessId(), p.getProcessId())))) {
            traceMapper.updateById(new MesProSnTraceDO().setId(trace.getId()).setStatus("COMPLETED"));
        }
        return station.getId();
    }

    private void validateFeedback(MesProFeedbackDO feedback, MesProSnTraceDO trace) {
        if (feedback == null || !MesProFeedbackStatusEnum.FINISHED.getStatus().equals(feedback.getStatus())
                || feedback.getFeedbackQuantity() == null || feedback.getFeedbackQuantity().signum() <= 0
                || feedback.getFeedbackQuantity().stripTrailingZeros().scale() > 0
                || feedback.getTaskId() == null || feedback.getRouteId() == null || feedback.getProcessId() == null
                || !Objects.equals(feedback.getWorkOrderId(), trace.getWorkOrderId())
                || !Objects.equals(feedback.getItemId(), trace.getProductId())) throw exception(FEEDBACK_INVALID);
        MesProTaskDO task = taskMapper.selectById(feedback.getTaskId());
        if (task == null || !Objects.equals(task.getWorkOrderId(), trace.getWorkOrderId())
                || !Objects.equals(task.getItemId(), trace.getProductId())
                || !Objects.equals(task.getRouteId(), feedback.getRouteId())
                || !Objects.equals(task.getProcessId(), feedback.getProcessId())
                || !Objects.equals(task.getWorkstationId(), feedback.getWorkstationId())) throw exception(FEEDBACK_INVALID);
    }

    /** This controlled registration follows the configured route's strict sort order. */
    static MesProRouteProcessDO validateSequence(MesProFeedbackDO feedback, List<MesProSnStationDO> history,
                                                  List<MesProRouteProcessDO> route) {
        if (route.isEmpty() || route.stream().anyMatch(p -> p.getSort() == null || p.getProcessId() == null)
                || route.stream().map(MesProRouteProcessDO::getSort).distinct().count() != route.size()
                || route.stream().map(MesProRouteProcessDO::getProcessId).distinct().count() != route.size()) throw exception(STATION_INVALID);
        MesProRouteProcessDO current = route.stream().filter(p -> Objects.equals(p.getProcessId(), feedback.getProcessId()))
                .findFirst().orElseThrow(() -> exception(STATION_INVALID));
        if (history.stream().anyMatch(h -> !Objects.equals(h.getRouteId(), feedback.getRouteId())
                || Objects.equals(h.getProcessId(), feedback.getProcessId())
                || h.getProcessSort() >= current.getSort())) throw exception(STATION_INVALID);
        for (MesProRouteProcessDO prior : route) {
            if (prior.getSort() < current.getSort() && history.stream().noneMatch(h ->
                    Objects.equals(h.getProcessId(), prior.getProcessId()) && Objects.equals(h.getProcessSort(), prior.getSort()))) {
                throw exception(STATION_INVALID);
            }
        }
        return current;
    }

    /** Compare by multiplication to avoid rounding a fractional single-piece material requirement. */
    static void validateShares(List<MesProSnTraceStationReqVO.Material> inputs, List<MesWmItemConsumeLineDO> lines,
                               List<MesWmItemConsumeDetailDO> details, List<MesProSnMaterialDO> allocated, BigDecimal output) {
        Map<Long, MesWmItemConsumeDetailDO> byId = details.stream().collect(Collectors.toMap(MesWmItemConsumeDetailDO::getId, d -> d));
        Map<Long, BigDecimal> used = new HashMap<>();
        allocated.forEach(a -> used.merge(a.getConsumeDetailId(), a.getQuantity(), BigDecimal::add));
        Map<Long, BigDecimal> perLine = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (MesProSnTraceStationReqVO.Material input : inputs) {
            MesWmItemConsumeDetailDO detail = byId.get(input.getConsumeDetailId());
            if (detail == null || !seen.add(input.getConsumeDetailId()) || input.getQuantity() == null
                    || input.getQuantity().signum() <= 0 || input.getQuantity().scale() > 6
                    || input.getQuantity().add(used.getOrDefault(detail.getId(), BigDecimal.ZERO)).compareTo(detail.getQuantity()) > 0) {
                throw exception(SHARE_INVALID);
            }
            perLine.merge(detail.getLineId(), input.getQuantity(), BigDecimal::add);
        }
        for (MesWmItemConsumeLineDO line : lines) {
            if (perLine.getOrDefault(line.getId(), BigDecimal.ZERO).multiply(output).compareTo(line.getQuantity()) != 0) {
                throw exception(SHARE_INVALID);
            }
        }
    }

    private Source source(MesProFeedbackDO feedback) {
        MesWmItemConsumeDO consume = consumeMapper.selectByFeedbackId(feedback.getId());
        if (consume == null) return new Source(List.of(), List.of());
        if (!MesWmItemConsumeStatusEnum.FINISHED.getStatus().equals(consume.getStatus())
                || !Objects.equals(consume.getWorkOrderId(), feedback.getWorkOrderId())
                || !Objects.equals(consume.getTaskId(), feedback.getTaskId())
                || !Objects.equals(consume.getProcessId(), feedback.getProcessId())
                || !Objects.equals(consume.getWorkstationId(), feedback.getWorkstationId())) throw exception(SOURCE_INVALID);
        List<MesWmItemConsumeLineDO> lines = lineMapper.selectListByConsumeId(consume.getId());
        List<MesWmItemConsumeDetailDO> details = detailMapper.selectListByConsumeId(consume.getId());
        MesWmWarehouseDO wip = warehouseMapper.selectByCode(MesWmWarehouseDO.WIP_VIRTUAL_WAREHOUSE);
        Map<Long, MesWmItemConsumeLineDO> lineMap = lines.stream().collect(Collectors.toMap(MesWmItemConsumeLineDO::getId, l -> l));
        List<MesWmTransactionDO> transactions = transactionMapper.selectList(new LambdaQueryWrapperX<MesWmTransactionDO>()
                .eq(MesWmTransactionDO::getBizType, MesBizTypeConstants.WM_ITEM_CONSUME)
                .eq(MesWmTransactionDO::getBizId, consume.getId()).eq(MesWmTransactionDO::getWorkOrderId, feedback.getWorkOrderId())
                .eq(MesWmTransactionDO::getType, MesWmTransactionTypeEnum.OUT.getType()));
        Map<SourceKey, BigDecimal> detailTotals = new HashMap<>();
        Map<SourceKey, BigDecimal> movementTotals = new HashMap<>();
        for (MesWmTransactionDO t : transactions) {
            if (t.getQuantity() == null || t.getQuantity().signum() >= 0) throw exception(SOURCE_INVALID);
            movementTotals.merge(sourceKey(t.getBizLineId(), t.getMaterialStockId(), t.getItemId(), t.getBatchId(), t.getBatchCode()),
                    t.getQuantity().negate(), BigDecimal::add);
        }
        for (MesWmItemConsumeDetailDO d : details) {
            MesWmItemConsumeLineDO line = lineMap.get(d.getLineId());
            MesWmMaterialStockDO stock = d.getMaterialStockId() == null ? null : stockMapper.selectById(d.getMaterialStockId());
            if (line == null || line.getQuantity() == null || line.getQuantity().signum() <= 0 || !Objects.equals(line.getItemId(), d.getItemId())
                    || d.getQuantity() == null || d.getQuantity().signum() <= 0 || stock == null || wip == null
                    || !Objects.equals(d.getWarehouseId(), wip.getId()) || !Objects.equals(stock.getWarehouseId(), wip.getId())
                    || !Objects.equals(stock.getWorkOrderId(), feedback.getWorkOrderId()) || !Objects.equals(stock.getItemId(), d.getItemId())
                    || !Objects.equals(stock.getBatchId(), d.getBatchId()) || !Objects.equals(stock.getBatchCode(), d.getBatchCode())) throw exception(SOURCE_INVALID);
            detailTotals.merge(sourceKey(d.getLineId(), d.getMaterialStockId(), d.getItemId(), d.getBatchId(), d.getBatchCode()), d.getQuantity(), BigDecimal::add);
        }
        for (MesWmItemConsumeLineDO line : lines) {
            BigDecimal total = details.stream().filter(d -> Objects.equals(d.getLineId(), line.getId()))
                    .map(MesWmItemConsumeDetailDO::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (line.getQuantity() == null || line.getQuantity().signum() <= 0 || total.compareTo(line.getQuantity()) != 0) throw exception(SOURCE_INVALID);
        }
        if (!detailTotals.keySet().equals(movementTotals.keySet()) || detailTotals.entrySet().stream().anyMatch(e ->
                e.getValue().compareTo(movementTotals.get(e.getKey())) != 0)) throw exception(SOURCE_INVALID);
        return new Source(lines, details);
    }

    private static SourceKey sourceKey(Long line, Long stock, Long item, Long batch, String code) {
        return new SourceKey(line, stock, item, batch, code);
    }

    static String requestHash(MesProSnTraceStationReqVO request) {
        String materials = request.getMaterials().stream().sorted(Comparator.comparing(MesProSnTraceStationReqVO.Material::getConsumeDetailId))
                .map(m -> m.getConsumeDetailId() + ":" + m.getQuantity().stripTrailingZeros().toPlainString()).collect(Collectors.joining(";"));
        return DigestUtil.sha256Hex(request.getTraceId() + "/" + request.getFeedbackId() + "/" + materials);
    }

    private List<MesProSnStationDO> stations(Long traceId) {
        return stationMapper.selectList(new LambdaQueryWrapperX<MesProSnStationDO>()
                .eq(MesProSnStationDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .eq(MesProSnStationDO::getTraceId, traceId).orderByAsc(MesProSnStationDO::getProcessSort));
    }

    @Override
    public PageResult<MesProSnTraceDO> page(MesProSnTracePageReqVO request) {
        return traceMapper.selectPage(request, new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .eqIfPresent(MesProSnTraceDO::getWorkOrderId, request.getWorkOrderId())
                .likeIfPresent(MesProSnTraceDO::getSnCode, request.getSnCode())
                .likeIfPresent(MesProSnTraceDO::getProductBatchCode, request.getBatchCode()).orderByDesc(MesProSnTraceDO::getId));
    }

    @Override
    public MesProSnTraceRespVO get(Long id) {
        MesProSnTraceDO trace = traceMapper.selectOne(new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getTenantId, TenantContextHolder.getRequiredTenantId()).eq(MesProSnTraceDO::getId, id));
        if (trace == null) throw exception(NOT_FOUND);
        List<MesProSnMaterialDO> materials = materialMapper.selectList(new LambdaQueryWrapperX<MesProSnMaterialDO>()
                .eq(MesProSnMaterialDO::getTenantId, TenantContextHolder.getRequiredTenantId()).eq(MesProSnMaterialDO::getTraceId, id));
        List<MesProSnTraceRespVO.Station> history = stations(id).stream().map(s -> {
            MesProSnTraceRespVO.Station row = BeanUtils.toBean(s, MesProSnTraceRespVO.Station.class);
            return row.setMaterials(materials.stream().filter(m -> Objects.equals(m.getStationId(), s.getId())).toList());
        }).toList();
        return new MesProSnTraceRespVO().setSn(trace).setStations(history);
    }

    @Override
    public List<MesProFeedbackDO> feedbackOptions(Long workOrderId) {
        if (workOrderMapper.selectById(workOrderId) == null) throw exception(NOT_FOUND);
        return feedbackMapper.selectList(new LambdaQueryWrapperX<MesProFeedbackDO>().eq(MesProFeedbackDO::getWorkOrderId, workOrderId)
                .eq(MesProFeedbackDO::getStatus, MesProFeedbackStatusEnum.FINISHED.getStatus()).orderByAsc(MesProFeedbackDO::getId));
    }

    @Override
    public List<MesProSnTraceRespVO.ConsumeOption> consumeOptions(Long feedbackId) {
        MesProFeedbackDO feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null || !MesProFeedbackStatusEnum.FINISHED.getStatus().equals(feedback.getStatus())
                || feedback.getFeedbackQuantity() == null || feedback.getFeedbackQuantity().signum() <= 0) throw exception(FEEDBACK_INVALID);
        Source source = source(feedback);
        Map<Long, BigDecimal> allocated = new HashMap<>();
        materialMapper.selectList(new LambdaQueryWrapperX<MesProSnMaterialDO>()
                .eq(MesProSnMaterialDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .eq(MesProSnMaterialDO::getFeedbackId, feedbackId)).forEach(m ->
                allocated.merge(m.getConsumeDetailId(), m.getQuantity(), BigDecimal::add));
        Map<Long, MesWmItemConsumeLineDO> lines = source.lines.stream().collect(Collectors.toMap(MesWmItemConsumeLineDO::getId, l -> l));
        return source.details.stream().map(d -> {
            BigDecimal unit;
            try { unit = lines.get(d.getLineId()).getQuantity().divide(feedback.getFeedbackQuantity()); }
            catch (ArithmeticException e) { throw exception(SHARE_INVALID); }
            return new MesProSnTraceRespVO.ConsumeOption().setConsumeDetailId(d.getId()).setConsumeLineId(d.getLineId())
                    .setItemId(d.getItemId()).setBatchId(d.getBatchId()).setBatchCode(d.getBatchCode()).setMaterialStockId(d.getMaterialStockId())
                    .setQuantity(d.getQuantity()).setAllocatedQuantity(allocated.getOrDefault(d.getId(), BigDecimal.ZERO))
                    .setAvailableQuantity(d.getQuantity().subtract(allocated.getOrDefault(d.getId(), BigDecimal.ZERO))).setUnitQuantity(unit);
        }).toList();
    }

    @Override
    public List<MesProSnTraceDO> affected(Long batchId, String batchCode) {
        if (batchId == null && StrUtil.isBlank(batchCode)) throw exception(SOURCE_INVALID);
        Set<Long> ids = materialMapper.selectList(new LambdaQueryWrapperX<MesProSnMaterialDO>()
                .eq(MesProSnMaterialDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .eqIfPresent(MesProSnMaterialDO::getBatchId, batchId).eqIfPresent(MesProSnMaterialDO::getBatchCode, StrUtil.trimToNull(batchCode)))
                .stream().map(MesProSnMaterialDO::getTraceId).collect(Collectors.toSet());
        if (ids.isEmpty()) return List.of();
        return traceMapper.selectList(new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getTenantId, TenantContextHolder.getRequiredTenantId()).in(MesProSnTraceDO::getId, ids)
                .orderByAsc(MesProSnTraceDO::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureSnUntracked(Collection<Long> snIds) {
        if (snIds.isEmpty()) return;
        // Shares the registration's SN row lock, closing the registration/deletion race.
        snMapper.selectList(new LambdaQueryWrapperX<MesWmSnDO>().in(MesWmSnDO::getId, snIds)
                .orderByAsc(MesWmSnDO::getId).last("FOR UPDATE"));
        // A current locking read is required: the caller may already have opened a
        // REPEATABLE_READ snapshot before waiting for a concurrent registration.
        if (!traceMapper.selectList(new LambdaQueryWrapperX<MesProSnTraceDO>()
                .eq(MesProSnTraceDO::getTenantId, TenantContextHolder.getRequiredTenantId())
                .in(MesProSnTraceDO::getSnId, snIds).last("FOR UPDATE")).isEmpty()) {
            throw exception(REFERENCED_SN);
        }
    }

    private record Source(List<MesWmItemConsumeLineDO> lines, List<MesWmItemConsumeDetailDO> details) { }
    private record SourceKey(Long line, Long stock, Long item, Long batch, String code) { }
}
