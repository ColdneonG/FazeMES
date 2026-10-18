package cn.iocoder.yudao.module.mes.service.dv.repair;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.MesDvRepairPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.MesDvRepairSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.MesDvRepairDO;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.repair.MesDvRepairMapper;
import cn.iocoder.yudao.module.mes.enums.dv.MesDvRepairStatusEnum;
import cn.iocoder.yudao.module.mes.service.dv.machinery.MesDvMachineryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.repair.MesDvRepairLineDO;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.repair.MesDvRepairLineMapper;
import cn.iocoder.yudao.module.mes.dal.mysql.dv.machinery.MesDvMachineryMapper;
import cn.iocoder.yudao.module.mes.dal.dataobject.dv.machinery.MesDvMachineryDO;
import cn.iocoder.yudao.module.mes.enums.dv.MesDvMachineryStatusEnum;
import cn.iocoder.yudao.module.mes.enums.dv.MesDvRepairResultEnum;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 维修工单 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesDvRepairServiceImpl implements MesDvRepairService {

    @Resource
    private MesDvRepairMapper repairMapper;
    @Resource private MesDvRepairLineMapper repairLineMapper;
    @Resource private MesDvMachineryMapper machineryMapper;

    @Resource
    private MesDvRepairLineService repairLineService;
    @Resource
    private MesDvMachineryService machineryService;

    @Override
    public Long createRepair(MesDvRepairSaveReqVO createReqVO) {
        // Automatic source keys are reserved for the transactional check/maintenance completion paths.
        if (Objects.equals(createReqVO.getSourceDocType(), 101) || Objects.equals(createReqVO.getSourceDocType(), 102)) {
            throw exception(DV_REPAIR_CHANGED);
        }
        // 1. 校验保存数据
        validateSaveData(createReqVO);

        // 2. 插入
        MesDvRepairDO repair = BeanUtils.toBean(createReqVO, MesDvRepairDO.class);
        repair.setStatus(MesDvRepairStatusEnum.PREPARE.getStatus());
        repairMapper.insert(repair);
        return repair.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRepair(MesDvRepairSaveReqVO updateReqVO) {
        // 1.1 校验存在，且状态为草稿
        MesDvRepairDO original=validateRepairPrepare(updateReqVO.getId());
        if((Objects.equals(original.getSourceDocType(),101) || Objects.equals(original.getSourceDocType(),102))
            && !Objects.equals(original.getMachineryId(),updateReqVO.getMachineryId())) throw exception(DV_REPAIR_CHANGED);
        // 1.2 校验保存数据
        validateSaveData(updateReqVO);

        // 2. 更新
        MesDvRepairDO updateObj = new MesDvRepairDO().setId(updateReqVO.getId())
            .setCode(updateReqVO.getCode()).setName(updateReqVO.getName()).setMachineryId(updateReqVO.getMachineryId())
            .setRequireDate(updateReqVO.getRequireDate()).setRemark(updateReqVO.getRemark());
        repairMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRepair(Long id) {
        // 校验存在，且状态为草稿
        MesDvRepairDO original=validateRepairPrepare(id);
        if(Objects.equals(original.getSourceDocType(),101) || Objects.equals(original.getSourceDocType(),102))
            throw exception(DV_REPAIR_AUTO_CANNOT_DELETE);

        // 删除
        repairMapper.deleteById(id);
        // 级联删除子表
        repairLineService.deleteByRepairId(id);
    }

    /**
     * 校验保存时的关联数据
     */
    private void validateSaveData(MesDvRepairSaveReqVO reqVO) {
        // 校验编码唯一
        validateCodeUnique(reqVO.getId(), reqVO.getCode());
        // 校验设备存在
        machineryService.validateMachineryExists(reqVO.getMachineryId());
    }

    private void validateCodeUnique(Long id, String code) {
        if (code == null) {
            return;
        }
        MesDvRepairDO repair = repairMapper.selectByCode(code);
        if (repair == null) {
            return;
        }
        if (ObjUtil.notEqual(id, repair.getId())) {
            throw exception(DV_REPAIR_CODE_DUPLICATE);
        }
    }

    @Override
    public MesDvRepairDO validateRepairExists(Long id) {
        MesDvRepairDO repair = repairMapper.selectById(id);
        if (repair == null) {
            throw exception(DV_REPAIR_NOT_EXISTS);
        }
        return repair;
    }

    @Override
    public Long getRepairCountByMachineryId(Long machineryId) {
        return repairMapper.selectCountByMachineryId(machineryId);
    }

    /**
     * 校验维修工单是否为草稿状态
     *
     * @param id 编号
     * @return 维修工单
     */
    public MesDvRepairDO validateRepairPrepare(Long id) {
        MesDvRepairDO repair = repairMapper.selectForUpdate(id);
        if (repair == null) throw exception(DV_REPAIR_NOT_EXISTS);
        if (ObjUtil.notEqual(MesDvRepairStatusEnum.PREPARE.getStatus(), repair.getStatus())) {
            throw exception(DV_REPAIR_NOT_PREPARE);
        }
        return repair;
    }

    @Override
    public MesDvRepairDO getRepair(Long id) {
        return repairMapper.selectById(id);
    }

    @Override
    public PageResult<MesDvRepairDO> getRepairPage(MesDvRepairPageReqVO pageReqVO) {
        return repairMapper.selectPage(pageReqVO);
    }

    /** Equipment -> repair is the common lock order for every state transition. */
    private MesDvRepairDO lockRepair(Long id) {
        MesDvRepairDO snapshot=validateRepairExists(id);
        if(machineryMapper.selectForUpdate(snapshot.getMachineryId())==null) throw exception(DV_MACHINERY_NOT_EXISTS);
        MesDvRepairDO current=repairMapper.selectForUpdate(id);
        if(current==null) throw exception(DV_REPAIR_NOT_EXISTS);
        if(!Objects.equals(snapshot.getMachineryId(),current.getMachineryId())) throw exception(DV_REPAIR_CHANGED);
        return current;
    }
    public MesDvRepairDO validateRepairEditable(Long id) {
        MesDvRepairDO repair=repairMapper.selectForUpdate(id);
        if(repair==null) throw exception(DV_REPAIR_NOT_EXISTS);
        if(!Objects.equals(repair.getStatus(),MesDvRepairStatusEnum.PREPARE.getStatus())
            && !Objects.equals(repair.getStatus(),MesDvRepairStatusEnum.CONFIRMED.getStatus()))
            throw exception(DV_REPAIR_NOT_EDITABLE);
        return repair;
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createAbnormalRepair(Long machineryId,Integer sourceType,Long sourceId,List<MesDvRepairLineDO> lines) {
        if(lines==null || lines.isEmpty()) return null;
        if(!Objects.equals(sourceType,101) && !Objects.equals(sourceType,102)) throw exception(DV_REPAIR_RESULT_INVALID);
        if(machineryMapper.selectForUpdate(machineryId)==null) throw exception(DV_MACHINERY_NOT_EXISTS);
        MesDvRepairDO existing=repairMapper.selectSourceForUpdate(sourceType,sourceId);
        if(existing!=null) return existing.getId();
        MesDvRepairDO repair=new MesDvRepairDO().setCode("DV-AUTO-"+sourceType+"-"+sourceId)
            .setName(sourceType==101?"点检异常自动报修":"保养异常自动报修").setMachineryId(machineryId)
            .setRequireDate(LocalDateTime.now()).setStatus(MesDvRepairStatusEnum.PREPARE.getStatus())
            .setSourceDocType(sourceType).setSourceDocId(sourceId)
            .setSourceDocCode((sourceType==101?"CHECK-":"MAINTEN-")+sourceId);
        repairMapper.insert(repair);
        for(var line:lines) repairLineMapper.insert(new MesDvRepairLineDO().setRepairId(repair.getId())
            .setSubjectId(line.getSubjectId()).setMalfunction(line.getMalfunction()).setMalfunctionUrl(line.getMalfunctionUrl()));
        machineryMapper.updateById(new MesDvMachineryDO().setId(machineryId).setStatus(MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
        return repair.getId();
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void submitRepair(Long id,Long userId) {
        MesDvRepairDO repair=lockRepair(id);
        if(!Objects.equals(repair.getStatus(),MesDvRepairStatusEnum.PREPARE.getStatus())) throw exception(DV_REPAIR_NOT_PREPARE);
        repairMapper.updateById(new MesDvRepairDO().setId(id).setStatus(MesDvRepairStatusEnum.CONFIRMED.getStatus()).setAcceptedUserId(userId));
        machineryMapper.updateById(new MesDvMachineryDO().setId(repair.getMachineryId()).setStatus(MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void confirmRepair(cn.iocoder.yudao.module.mes.controller.admin.dv.repair.vo.MesDvRepairConfirmReqVO req) {
        MesDvRepairDO repair=lockRepair(req.getId());
        if(!Objects.equals(repair.getStatus(),MesDvRepairStatusEnum.CONFIRMED.getStatus())) throw exception(DV_REPAIR_NOT_CONFIRMED);
        repairMapper.updateById(new MesDvRepairDO().setId(req.getId()).setStatus(MesDvRepairStatusEnum.APPROVING.getStatus())
            .setFinishDate(req.getFinishDate()));
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void finishRepair(Long id,Integer result,Long userId) {
        if(!Objects.equals(result,MesDvRepairResultEnum.PASS.getResult()) && !Objects.equals(result,MesDvRepairResultEnum.FAIL.getResult()))
            throw exception(DV_REPAIR_RESULT_INVALID);
        MesDvRepairDO repair=lockRepair(id);
        if(!Objects.equals(repair.getStatus(),MesDvRepairStatusEnum.APPROVING.getStatus())) throw exception(DV_REPAIR_NOT_APPROVING);
        boolean passed=Objects.equals(result,MesDvRepairResultEnum.PASS.getResult());
        // A failed inspection returns the order to repair; it never restores an unsafe machine.
        repairMapper.updateById(new MesDvRepairDO().setId(id)
            .setStatus(passed?MesDvRepairStatusEnum.FINISHED.getStatus():MesDvRepairStatusEnum.CONFIRMED.getStatus())
            .setResult(result).setConfirmUserId(userId).setConfirmDate(LocalDateTime.now()));
        boolean hasOpen=!repairMapper.selectUnfinishedForUpdate(repair.getMachineryId()).isEmpty();
        machineryMapper.updateById(new MesDvMachineryDO().setId(repair.getMachineryId()).setStatus(
            passed && !hasOpen?MesDvMachineryStatusEnum.STOP.getStatus():MesDvMachineryStatusEnum.MAINTENANCE.getStatus()));
    }
}
