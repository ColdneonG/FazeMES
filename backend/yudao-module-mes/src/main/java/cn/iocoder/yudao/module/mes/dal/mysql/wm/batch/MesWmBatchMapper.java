package cn.iocoder.yudao.module.mes.dal.mysql.wm.batch;


import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.mes.controller.admin.wm.batch.vo.MesWmBatchPageReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.wm.batch.MesWmBatchDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 批次管理 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface MesWmBatchMapper extends BaseMapperX<MesWmBatchDO> {

    default MesWmBatchDO selectByCode(String code) {
        return selectOne(MesWmBatchDO:: getCode, code);
    }

    default Long selectCountByToolId(Long toolId) {
        return selectCount(MesWmBatchDO::getToolId, toolId);
    }

    default PageResult<MesWmBatchDO> selectPage(MesWmBatchPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MesWmBatchDO>()
                .likeIfPresent(MesWmBatchDO::getCode, reqVO.getCode())
                .eqIfPresent(MesWmBatchDO::getItemId, reqVO.getItemId())
                .eqIfPresent(MesWmBatchDO::getVendorId, reqVO.getVendorId())
                .eqIfPresent(MesWmBatchDO::getClientId, reqVO.getClientId())
                .likeIfPresent(MesWmBatchDO::getSalesOrderCode, reqVO.getSalesOrderCode())
                .likeIfPresent(MesWmBatchDO::getPurchaseOrderCode, reqVO.getPurchaseOrderCode())
                .eqIfPresent(MesWmBatchDO::getWorkOrderId, reqVO.getWorkOrderId())
                .eqIfPresent(MesWmBatchDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(MesWmBatchDO::getWorkstationId, reqVO.getWorkstationId())
                .eqIfPresent(MesWmBatchDO::getToolId, reqVO.getToolId())
                .eqIfPresent(MesWmBatchDO::getMoldId, reqVO.getMoldId())
                .likeIfPresent(MesWmBatchDO::getLotNumber, reqVO.getLotNumber())
                .eqIfPresent(MesWmBatchDO::getQualityStatus, reqVO.getQualityStatus())
                .betweenIfPresent(MesWmBatchDO::getProduceDate, reqVO.getProduceDate())
                .betweenIfPresent(MesWmBatchDO::getExpireDate, reqVO.getExpireDate())
                .betweenIfPresent(MesWmBatchDO::getReceiptDate, reqVO.getReceiptDate())
                .orderByDesc(MesWmBatchDO::getId));
    }

    /**
     * 根据参数查询匹配的第一条批次记录
     * <p>
     * 使用 NULL 值精确匹配，返回 ID 最小的批次
     *
     * @param batch 批次参数
     * @return 匹配的批次记录
     */
    default MesWmBatchDO selectFirst(MesWmBatchDO batch) {
        LambdaQueryWrapper<MesWmBatchDO> query = new LambdaQueryWrapper<>();
        query.eq(MesWmBatchDO::getItemId, batch.getItemId());
        if (ObjUtil.isNull(batch.getVendorId())) {
            query.isNull(MesWmBatchDO::getVendorId);
        } else {
            query.eq(MesWmBatchDO::getVendorId, batch.getVendorId());
        }
        if (ObjUtil.isNull(batch.getClientId())) {
            query.isNull(MesWmBatchDO::getClientId);
        } else {
            query.eq(MesWmBatchDO::getClientId, batch.getClientId());
        }
        if (ObjUtil.isNull(batch.getSalesOrderCode())) {
            query.isNull(MesWmBatchDO::getSalesOrderCode);
        } else {
            query.eq(MesWmBatchDO::getSalesOrderCode, batch.getSalesOrderCode());
        }
        if (ObjUtil.isNull(batch.getPurchaseOrderCode())) {
            query.isNull(MesWmBatchDO::getPurchaseOrderCode);
        } else {
            query.eq(MesWmBatchDO::getPurchaseOrderCode, batch.getPurchaseOrderCode());
        }
        if (ObjUtil.isNull(batch.getWorkOrderId())) {
            query.isNull(MesWmBatchDO::getWorkOrderId);
        } else {
            query.eq(MesWmBatchDO::getWorkOrderId, batch.getWorkOrderId());
        }
        if (ObjUtil.isNull(batch.getTaskId())) {
            query.isNull(MesWmBatchDO::getTaskId);
        } else {
            query.eq(MesWmBatchDO::getTaskId, batch.getTaskId());
        }
        if (ObjUtil.isNull(batch.getWorkstationId())) {
            query.isNull(MesWmBatchDO::getWorkstationId);
        } else {
            query.eq(MesWmBatchDO::getWorkstationId, batch.getWorkstationId());
        }
        if (ObjUtil.isNull(batch.getToolId())) {
            query.isNull(MesWmBatchDO::getToolId);
        } else {
            query.eq(MesWmBatchDO::getToolId, batch.getToolId());
        }
        if (ObjUtil.isNull(batch.getMoldId())) {
            query.isNull(MesWmBatchDO::getMoldId);
        } else {
            query.eq(MesWmBatchDO::getMoldId, batch.getMoldId());
        }
        if (ObjUtil.isNull(batch.getLotNumber())) {
            query.isNull(MesWmBatchDO::getLotNumber);
        } else {
            query.eq(MesWmBatchDO::getLotNumber, batch.getLotNumber());
        }
        if (ObjUtil.isNull(batch.getQualityStatus())) {
            query.isNull(MesWmBatchDO::getQualityStatus);
        } else {
            query.eq(MesWmBatchDO::getQualityStatus, batch.getQualityStatus());
        }
        if (ObjUtil.isNull(batch.getProduceDate())) {
            query.isNull(MesWmBatchDO::getProduceDate);
        } else {
            query.eq(MesWmBatchDO::getProduceDate, batch.getProduceDate());
        }
        if (ObjUtil.isNull(batch.getExpireDate())) {
            query.isNull(MesWmBatchDO::getExpireDate);
        } else {
            query.eq(MesWmBatchDO::getExpireDate, batch.getExpireDate());
        }
        if (ObjUtil.isNull(batch.getReceiptDate())) {
            query.isNull(MesWmBatchDO::getReceiptDate);
        } else {
            query.eq(MesWmBatchDO::getReceiptDate, batch.getReceiptDate());
        }

        // 返回 ID 最小的批次
        query.orderByAsc(MesWmBatchDO::getId);
        query.last("LIMIT 1");
        return selectOne(query);
    }

    /**
     * 查询向前追溯批次列表
     * <p>
     * 沿同次报工、已冻结工序来源闭包或已完成 SN 分配，返回实际产出批次。
     * 没有流转或SN证据的历史消耗不能仅凭工单推断归属。
     */
    List<MesWmBatchDO> selectListByForward(@Param("batchCode") String batchCode,
                                         @Param("tenantId") Long tenantId);

    /**
     * 查询向后追溯批次列表
     * <p>
     * 沿同次报工、已冻结工序来源闭包或已完成 SN 分配，返回实际消耗批次。
     * 结果为共享批次池的已记录关系；完整性由独立覆盖接口核验，不表示单件SN。
     */
    List<MesWmBatchDO> selectListByBackward(@Param("batchCode") String batchCode,
                                          @Param("tenantId") Long tenantId);

}
