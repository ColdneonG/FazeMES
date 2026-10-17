package cn.iocoder.yudao.module.mes.service.md.workstation;

import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.worker.MesMdWorkstationWorkerSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.workstation.MesMdWorkstationWorkerDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.workstation.MesMdWorkstationWorkerMapper;
import cn.iocoder.yudao.module.system.api.dept.PostApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import java.util.HashSet;
import java.util.ArrayList;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 人力资源 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesMdWorkstationWorkerServiceImpl implements MesMdWorkstationWorkerService {

    @Resource
    private MesMdWorkstationWorkerMapper workstationWorkerMapper;

    @Resource
    private PostApi postApi;
    @Resource
    private AdminUserApi adminUserApi;

    @Override
    public Long createWorkstationWorker(MesMdWorkstationWorkerSaveReqVO createReqVO) {
        // 校验数据
        validateWorkstationWorkerSaveData(null, createReqVO);

        // 插入
        MesMdWorkstationWorkerDO worker = BeanUtils.toBean(createReqVO, MesMdWorkstationWorkerDO.class);
        workstationWorkerMapper.insert(worker);
        return worker.getId();
    }

    @Override
    public void updateWorkstationWorker(MesMdWorkstationWorkerSaveReqVO updateReqVO) {
        // 校验存在
        validateWorkstationWorkerExists(updateReqVO.getId());
        // 校验数据
        validateWorkstationWorkerSaveData(updateReqVO.getId(), updateReqVO);

        // 更新
        MesMdWorkstationWorkerDO updateObj = BeanUtils.toBean(updateReqVO, MesMdWorkstationWorkerDO.class);
        workstationWorkerMapper.updateById(updateObj);
    }

    private void validateWorkstationWorkerSaveData(Long id, MesMdWorkstationWorkerSaveReqVO reqVO) {
        if (reqVO.getQuantity() == null || reqVO.getQuantity() < 1) {
            throw exception(MD_WORKSTATION_RESOURCE_QUANTITY_INVALID);
        }
        // 校验岗位是否存在
        postApi.validPostList(Collections.singleton(reqVO.getPostId()));
        if (reqVO.getUserIds() != null && !reqVO.getUserIds().isEmpty()) {
            if (reqVO.getUserIds().stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(reqVO.getUserIds()).size() != reqVO.getUserIds().size()) {
                throw exception(MD_WORKSTATION_RESOURCE_BINDING_INVALID, "人员不可为空或重复");
            }
            for (Long userId : reqVO.getUserIds()) {
                var user = adminUserApi.validateUser(userId);
                if (user.getPostIds() == null || !user.getPostIds().contains(reqVO.getPostId())) {
                    throw exception(MD_WORKSTATION_RESOURCE_BINDING_INVALID, "人员不属于所选岗位");
                }
            }
            for (var other : workstationWorkerMapper.selectListByWorkstationId(reqVO.getWorkstationId())) {
                if (ObjUtil.notEqual(other.getId(), id) && other.getUserIds() != null
                        && !Collections.disjoint(other.getUserIds(), reqVO.getUserIds())) {
                    throw exception(MD_WORKSTATION_RESOURCE_BINDING_INVALID, "同一人员不能在此工作站重复计入其他岗位");
                }
            }
            reqVO.setUserIds(new ArrayList<>(reqVO.getUserIds()));
        }
        // 校验同一工作站下岗位不重复（排除自身）
        MesMdWorkstationWorkerDO existing = workstationWorkerMapper.selectByWorkstationIdAndPostId(
                reqVO.getWorkstationId(), reqVO.getPostId());
        if (existing != null && ObjUtil.notEqual(existing.getId(), id)) {
            throw exception(MD_WORKSTATION_WORKER_POST_EXISTS);
        }
    }

    @Override
    public void deleteWorkstationWorker(Long id) {
        // 校验存在
        validateWorkstationWorkerExists(id);

        // 删除
        workstationWorkerMapper.deleteById(id);
    }

    private void validateWorkstationWorkerExists(Long id) {
        if (workstationWorkerMapper.selectById(id) == null) {
            throw exception(MD_WORKSTATION_WORKER_NOT_EXISTS);
        }
    }

    @Override
    public List<MesMdWorkstationWorkerDO> getWorkstationWorkerListByWorkstationId(Long workstationId) {
        return workstationWorkerMapper.selectListByWorkstationId(workstationId);
    }

    @Override
    public void deleteWorkstationWorkerByWorkstationId(Long workstationId) {
        workstationWorkerMapper.deleteByWorkstationId(workstationId);
    }

}
