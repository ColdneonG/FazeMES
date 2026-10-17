package cn.iocoder.yudao.module.mes.controller.admin.pro.workbench.vo;

import cn.iocoder.yudao.module.mes.controller.admin.md.item.vo.sop.MesMdProductSopRespVO;
import lombok.Data;
import java.util.List;

@Data
public class MesProWorkbenchContextRespVO {
    private MesProWorkbenchTaskRespVO task;
    private List<MesMdProductSopRespVO> sops;
    private List<UserOption> approvers;

    @Data
    public static class UserOption {
        private Long id;
        private String name;
    }
}
