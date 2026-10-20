package cn.iocoder.yudao.module.mes.service.wm.productissue;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.wm.productissue.vo.detail.MesWmProductIssueDetailSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueDetailDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueDO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.productissue.MesWmProductIssueLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.wm.productissue.MesWmProductIssueDetailMapper;
import cn.iocoder.yudao.module.mes.enums.wm.MesWmProductIssueStatusEnum;
import cn.iocoder.yudao.module.mes.service.wm.materialstock.MesWmMaterialStockService;
import cn.iocoder.yudao.module.mes.service.wm.warehouse.MesWmWarehouseAreaService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 领料出库明细 Service 实现类
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Validated
public class MesWmProductIssueDetailServiceImpl implements MesWmProductIssueDetailService {

    @Resource
    private MesWmProductIssueDetailMapper issueDetailMapper;

    @Resource
    @Lazy
    private MesWmProductIssueService issueService;
    @Resource
    @Lazy
    private MesWmProductIssueLineService issueLineService;
    @Resource
    private MesWmWarehouseAreaService warehouseAreaService;
    @Resource
    private MesWmMaterialStockService materialStockService;

    @Override
    public Long createProductIssueDetail(MesWmProductIssueDetailSaveReqVO createReqVO) {
        // 校验数据
        validateProductIssueDetailSaveData(createReqVO);

        // 插入
        MesWmProductIssueDetailDO detail = BeanUtils.toBean(createReqVO, MesWmProductIssueDetailDO.class);
        issueDetailMapper.insert(detail);
        return detail.getId();
    }

    @Override
    public void updateProductIssueDetail(MesWmProductIssueDetailSaveReqVO updateReqVO) {
        MesWmProductIssueDetailDO current = lockOriginalDetail(updateReqVO.getId(), false);
        if (!Objects.equals(current.getIssueId(),updateReqVO.getIssueId()) || !Objects.equals(current.getLineId(),updateReqVO.getLineId())) {
            throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        // 校验数据
        validateProductIssueDetailSaveData(updateReqVO);

        // 更新
        MesWmProductIssueDetailDO updateObj = BeanUtils.toBean(updateReqVO, MesWmProductIssueDetailDO.class);
        issueDetailMapper.updateById(updateObj);
    }

    @Override
    public void deleteProductIssueDetail(Long id) {
        lockOriginalDetail(id, true);
        issueDetailMapper.deleteById(id);
    }

    @Override
    public MesWmProductIssueDetailDO getProductIssueDetail(Long id) {
        return issueDetailMapper.selectById(id);
    }

    @Override
    public List<MesWmProductIssueDetailDO> getProductIssueDetailListByLineId(Long lineId) {
        return issueDetailMapper.selectListByLineId(lineId);
    }

    @Override
    public List<MesWmProductIssueDetailDO> getProductIssueDetailListByIssueId(Long issueId) {
        return issueDetailMapper.selectListByIssueId(issueId);
    }

    @Override
    public void deleteProductIssueDetailByIssueId(Long issueId) {
        issueService.validateProductIssueExistsAndPrepare(issueId);
        List<MesWmProductIssueLineDO> lines=issueLineService.getProductIssueLineListByIssueIdForUpdate(issueId);
        Map<Long,MesWmProductIssueLineDO> byId=lines.stream().collect(Collectors.toMap(MesWmProductIssueLineDO::getId,Function.identity()));
        for(var detail:issueDetailMapper.selectListByIssueIdForUpdate(issueId)) {
            MesWmProductIssueLineDO line=byId.get(detail.getLineId());
            if(line==null || !Objects.equals(detail.getIssueId(),line.getIssueId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        // A corrupt historical detail must not be silently erased through another parent's cascade.
        for(var line:lines)for(var detail:issueDetailMapper.selectListByLineIdForUpdate(line.getId())) {
            if(!Objects.equals(detail.getIssueId(),issueId))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        issueDetailMapper.deleteByIssueId(issueId);
    }

    @Override
    public void deleteProductIssueDetailByLineId(Long lineId) {
        MesWmProductIssueLineDO located=issueLineService.validateProductIssueLineExists(lineId);
        issueService.validateProductIssueExistsAndPrepare(located.getIssueId());
        MesWmProductIssueLineDO current=issueLineService.validateProductIssueLineExistsForUpdate(lineId);
        if(!Objects.equals(current.getIssueId(),located.getIssueId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        for(var detail:issueDetailMapper.selectListByLineIdForUpdate(lineId)) {
            if(!Objects.equals(detail.getIssueId(),current.getIssueId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        issueDetailMapper.deleteByLineId(lineId);
    }
    @Override public List<MesWmProductIssueDetailDO> getProductIssueDetailListByIssueIdForUpdate(Long issueId) {
        return issueDetailMapper.selectListByIssueIdForUpdate(issueId);
    }
    @Override public List<MesWmProductIssueDetailDO> getProductIssueDetailListByLineIdForUpdate(Long lineId) {
        return issueDetailMapper.selectListByLineIdForUpdate(lineId);
    }
    /** Preliminary reads locate identity only; decisions use locked rows after the original parent lock. */
    private MesWmProductIssueDetailDO lockOriginalDetail(Long id,boolean deleting) {
        MesWmProductIssueDetailDO located=issueDetailMapper.selectById(id);
        if(located==null)throw exception(WM_PRODUCT_ISSUE_DETAIL_NOT_EXISTS);
        MesWmProductIssueLineDO locatedLine=issueLineService.validateProductIssueLineExists(located.getLineId());
        if(!Objects.equals(located.getIssueId(),locatedLine.getIssueId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        MesWmProductIssueDO issue=issueService.validateProductIssueExists(located.getIssueId());
        if(!Objects.equals(issue.getStatus(),MesWmProductIssueStatusEnum.APPROVING.getStatus())
                && !(deleting && Objects.equals(issue.getStatus(),MesWmProductIssueStatusEnum.PREPARE.getStatus())))throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        MesWmProductIssueLineDO line=issueLineService.validateProductIssueLineExistsForUpdate(located.getLineId());
        MesWmProductIssueDetailDO current=issueDetailMapper.selectByIdForUpdate(id);
        if(current==null)throw exception(WM_PRODUCT_ISSUE_DETAIL_NOT_EXISTS);
        if(!Objects.equals(line.getIssueId(),located.getIssueId()) || !Objects.equals(current.getIssueId(),located.getIssueId()) || !Objects.equals(current.getLineId(),located.getLineId()))throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        return current;
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateProductIssueDetailSaveData(MesWmProductIssueDetailSaveReqVO reqVO) {
        // Parent precedes child locks, including create; a prior snapshot cannot supply item or ownership.
        MesWmProductIssueDO issue = issueService.validateProductIssueExists(reqVO.getIssueId());
        MesWmProductIssueLineDO line = issueLineService.validateProductIssueLineExistsForUpdate(reqVO.getLineId());
        if (ObjUtil.notEqual(line.getIssueId(), reqVO.getIssueId())) {
            throw exception(WM_PRODUCT_ISSUE_DETAIL_LINE_NOT_MATCH);
        }
        // 校验主单状态为待拣货（只有 APPROVING 状态才允许新增/修改明细）
        if (ObjUtil.notEqual(MesWmProductIssueStatusEnum.APPROVING.getStatus(), issue.getStatus())) {
            throw exception(WM_PRODUCT_ISSUE_STATUS_INVALID);
        }
        // 校验物料匹配（明细 itemId 必须与行 itemId 一致）
        validateDetailItemMatch(reqVO.getItemId(), line.getItemId());
        if(line.getBatchId()!=null && !Objects.equals(line.getBatchId(),reqVO.getBatchId()))throw exception(WM_MATERIAL_STOCK_SELECTION_MISMATCH);
        // 校验仓库、库区、库位的关联关系
        warehouseAreaService.validateWarehouseAreaExists(
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId());
        // 校验库存记录
        materialStockService.validateSelectedStock(
                reqVO.getMaterialStockId(), reqVO.getItemId(), reqVO.getBatchId(), reqVO.getBatchCode(),
                reqVO.getWarehouseId(), reqVO.getLocationId(), reqVO.getAreaId(), reqVO.getQuantity());
    }

    /**
     * 校验明细物料与行物料一致
     */
    private void validateDetailItemMatch(Long detailItemId, Long lineItemId) {
        if (ObjUtil.notEqual(detailItemId, lineItemId)) {
            throw exception(WM_PRODUCT_ISSUE_DETAIL_ITEM_MISMATCH);
        }
    }

}
