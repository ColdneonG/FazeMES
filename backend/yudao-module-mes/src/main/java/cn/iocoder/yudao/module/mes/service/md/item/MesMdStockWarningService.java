package cn.iocoder.yudao.module.mes.service.md.item;

import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.MesMdStockWarningRespVO;
import cn.iocoder.yudao.module.mes.dal.mysql.md.item.MesMdStockWarningMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
public class MesMdStockWarningService {
    @Resource
    private MesMdStockWarningMapper mapper;

    public List<MesMdStockWarningRespVO> getWarnings(String keyword, String level) {
        if (level != null && !level.isBlank() && !List.of("LOW", "HIGH").contains(level)) {
            throw new IllegalArgumentException("库存预警级别只支持LOW/HIGH");
        }
        return mapper.selectStockLevels(keyword).stream().filter(item -> {
            BigDecimal quantity = item.getStockQuantity();
            if (quantity.compareTo(item.getMinStock()) < 0) {
                item.setLevel("LOW");
                item.setDifference(item.getMinStock().subtract(quantity));
            } else if (item.getMaxStock().signum() > 0 && quantity.compareTo(item.getMaxStock()) > 0) {
                item.setLevel("HIGH");
                item.setDifference(quantity.subtract(item.getMaxStock()));
            } else {
                return false;
            }
            return level == null || level.isBlank() || item.getLevel().equals(level);
        }).toList();
    }
}
