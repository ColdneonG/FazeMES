package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.bom.MesMdProductBomPageReqVO;
import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.bom.MesMdProductBomSaveReqVO;
import cn.iocoder.yudao.module.mes.dal.dataobject.md.item.MesMdProductBomDO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdProductBomMapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;
import cn.iocoder.yudao.module.mes.service.pro.integrity.MesProductionIntegrityService;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.mes.enums.ErrorCodeConstants.*;

/**
 * MES 产品BOM Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MesMdProductBomServiceImpl implements MesMdProductBomService {

    @Resource
    private MesMdProductBomMapper productBomMapper;

    @Resource
    @Lazy // 避免循环依赖
    private MesMdItemService itemService;
    @Resource
    private MesProductionIntegrityService integrityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProductBom(MesMdProductBomSaveReqVO createReqVO) {
        integrityService.lockBomGraph();
        integrityService.validateBomEditable(createReqVO.getItemId());
        if (createReqVO.getStatus() == null) createReqVO.setStatus(0);
        // 1.1 校验数据
        validateProductBomSaveData(createReqVO);
        // 1.2 校验不能形成闭环
        if (hasBomCycle(createReqVO.getItemId(), createReqVO.getBomItemId(), null)) {
            throw exception(MD_PRODUCT_BOM_CIRCULAR);
        }

        // 2. 插入
        MesMdProductBomDO productBom = BeanUtils.toBean(createReqVO, MesMdProductBomDO.class);
        productBomMapper.insert(productBom);
        return productBom.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProductBom(MesMdProductBomSaveReqVO updateReqVO) {
        integrityService.lockBomGraph();
        // 1.1 校验存在
        validateProductBomExists(updateReqVO.getId());
        MesMdProductBomDO original = productBomMapper.selectById(updateReqVO.getId());
        if (!Objects.equals(original.getItemId(), updateReqVO.getItemId())) {
            throw MesProductionIntegrityService.invalid("BOM行不能移至另一个产品，请删除后重新添加");
        }
        integrityService.validateBomEditable(original.getItemId());
        // 1.2 校验数据
        validateProductBomSaveData(updateReqVO);
        if (hasBomCycle(updateReqVO.getItemId(), updateReqVO.getBomItemId(), updateReqVO.getId())) {
            throw exception(MD_PRODUCT_BOM_CIRCULAR);
        }

        // 更新
        MesMdProductBomDO updateObj = BeanUtils.toBean(updateReqVO, MesMdProductBomDO.class);
        productBomMapper.updateById(updateObj);
    }

    private void validateProductBomSaveData(MesMdProductBomSaveReqVO reqVO) {
        if (reqVO.getItemId() == null || reqVO.getBomItemId() == null || reqVO.getQuantity() == null
                || reqVO.getQuantity().signum() <= 0 || reqVO.getQuantity().scale() > 6
                || reqVO.getQuantity().precision() - reqVO.getQuantity().scale() > 14) {
            throw MesProductionIntegrityService.invalid("BOM物料不能为空，用量必须为正数且最多六位小数");
        }
        if (reqVO.getStatus() != null && reqVO.getStatus() != 0 && reqVO.getStatus() != 1) {
            throw MesProductionIntegrityService.invalid("BOM状态不正确");
        }
        // 校验物料产品存在
        validateItemExists(reqVO.getItemId());
        // 校验BOM物料存在
        validateItemExists(reqVO.getBomItemId());
        // 校验不能自引用
        if (reqVO.getItemId().equals(reqVO.getBomItemId())) {
            throw exception(MD_PRODUCT_BOM_SELF_REFERENCE);
        }
        boolean duplicate = productBomMapper.selectAllForUpdate().stream().anyMatch(row ->
                Objects.equals(row.getItemId(), reqVO.getItemId())
                        && Objects.equals(row.getBomItemId(), reqVO.getBomItemId())
                        && !Objects.equals(row.getId(), reqVO.getId()));
        if (duplicate) throw MesProductionIntegrityService.invalid("同一产品不能重复添加相同BOM物料");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductBom(Long id) {
        integrityService.lockBomGraph();
        // 校验存在
        validateProductBomExists(id);
        integrityService.validateBomEditable(productBomMapper.selectById(id).getItemId());
        // 删除
        productBomMapper.deleteById(id);
    }

    private void validateProductBomExists(Long id) {
        if (productBomMapper.selectById(id) == null) {
            throw exception(MD_PRODUCT_BOM_NOT_EXISTS);
        }
    }

    private void validateItemExists(Long itemId) {
        if (itemService.getItem(itemId) == null) {
            throw exception(MD_ITEM_NOT_EXISTS);
        }
    }

    /**
     * 检测新增边 (itemId -> bomItemId) 后，BOM 图是否存在闭环
     *
     * @param itemId 父物料编号
     * @param bomItemId BOM子物料编号
     * @return 是否存在闭环
     */
    private boolean hasBomCycle(Long itemId, Long bomItemId, Long excludingId) {
        Map<Long, Set<Long>> graph = new HashMap<>();
        for (MesMdProductBomDO row : productBomMapper.selectAllForUpdate()) {
            if (!Objects.equals(row.getId(), excludingId)) {
                graph.computeIfAbsent(row.getItemId(), ignored -> new HashSet<>()).add(row.getBomItemId());
            }
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.push(bomItemId);
        while (!pending.isEmpty()) {
            Long node = pending.pop();
            if (itemId.equals(node)) return true;
            if (!visited.add(node)) continue;
            if (visited.size() > 10000) throw MesProductionIntegrityService.invalid("BOM结构过大，请拆分检查");
            graph.getOrDefault(node, Set.of()).forEach(pending::push);
        }
        return false;
    }

    @Override
    public MesMdProductBomDO getProductBom(Long id) {
        return productBomMapper.selectById(id);
    }

    @Override
    public PageResult<MesMdProductBomDO> getProductBomPage(MesMdProductBomPageReqVO pageReqVO) {
        return productBomMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MesMdProductBomDO> getProductBomListByItemId(Long itemId) {
        return productBomMapper.selectByItemId(itemId);
    }

    @Override
    public List<MesMdProductBomDO> getProductBomListByItemIds(Collection<Long> itemIds) {
        return productBomMapper.selectByItemIds(itemIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductBomByItemId(Long itemId) {
        integrityService.lockBomGraph();
        integrityService.validateBomEditable(itemId);
        productBomMapper.deleteByItemId(itemId);
    }

}
