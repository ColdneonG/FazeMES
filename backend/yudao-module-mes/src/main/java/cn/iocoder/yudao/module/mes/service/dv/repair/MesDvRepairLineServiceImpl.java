package cn.iocoder.yudao.module.mes.service.dv.repair;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.line.MesDvRepairLinePageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.line.MesDvRepairLineSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.MesDvRepairLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.repair.MesDvRepairLineMapper;
import cn.iocoder.yudao.module.mes.service.dv.subject.MesDvSubjectService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 维修工单行 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesDvRepairLineServiceImpl implements MesDvRepairLineService {

    @Resource
    private MesDvRepairLineMapper repairLineMapper;

    @Resource
    @Lazy
    private MesDvRepairServiceImpl repairService;
    @Resource
    private MesDvSubjectService subjectService;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createRepairLine(MesDvRepairLineSaveReqVO createReqVO) {
        // 1.1 校验关联数据
        validateRepairLineRelation(createReqVO);
        // 1.2 校验维修工单为草稿状态
        repairService.validateRepairPrepare(createReqVO.getRepairId());

        // 2. 插入
        MesDvRepairLineDO repairLine = BeanUtils.toBean(createReqVO, MesDvRepairLineDO.class);
        repairLineMapper.insert(repairLine);
        return repairLine.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void updateRepairLine(MesDvRepairLineSaveReqVO updateReqVO) {
        MesDvRepairLineDO original=repairLineMapper.selectById(updateReqVO.getId());
        if(original==null) throw exception(DV_REPAIR_LINE_NOT_EXISTS);
        if(!java.util.Objects.equals(original.getRepairId(),updateReqVO.getRepairId())) throw exception(DV_REPAIR_CHANGED);
        var repair=repairService.validateRepairEditable(original.getRepairId());
        if(repair.getStatus().equals(cn.iocoder.yudao.module.mes.enums.dv.MesDvRepairStatusEnum.CONFIRMED.getStatus())) {
            // During repair only the process narrative may change; keep original faults intact.
            repairLineMapper.updateById(new MesDvRepairLineDO().setId(original.getId())
                .setDescription(updateReqVO.getDescription()).setRemark(updateReqVO.getRemark()));
        } else {
            validateRepairLineRelation(updateReqVO);
            repairLineMapper.updateById(BeanUtils.toBean(updateReqVO,MesDvRepairLineDO.class));
        }
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void deleteRepairLine(Long id) {
        // 1.1 校验存在
        MesDvRepairLineDO line = repairLineMapper.selectById(id);
        if (line == null) {
            throw exception(DV_REPAIR_LINE_NOT_EXISTS);
        }
        // 1.2 校验维修工单为草稿状态
        repairService.validateRepairPrepare(line.getRepairId());

        // 2. 删除
        repairLineMapper.deleteById(id);
    }

    private void validateRepairLineRelation(MesDvRepairLineSaveReqVO reqVO) {
        // 校验维修工单是否存在
        repairService.validateRepairExists(reqVO.getRepairId());
        // 校验点检保养项目是否存在（可选）
        if (reqVO.getSubjectId() != null) {
            subjectService.validateSubjectExistsAndEnable(reqVO.getSubjectId());
        }
    }

    private void validateRepairLineExists(Long id) {
        if (repairLineMapper.selectById(id) == null) {
            throw exception(DV_REPAIR_LINE_NOT_EXISTS);
        }
    }

    @Override
    public MesDvRepairLineDO getRepairLine(Long id) {
        return repairLineMapper.selectById(id);
    }

    @Override
    public PageResult<MesDvRepairLineDO> getRepairLinePage(MesDvRepairLinePageReqVO pageReqVO) {
        return repairLineMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesDvRepairLineDO> getRepairLineListByRepairId(Long repairId) {
        return repairLineMapper.selectListByRepairId(repairId);
    }

    @Override
    public void deleteByRepairId(Long repairId) {
        repairLineMapper.deleteByRepairId(repairId);
    }

}
