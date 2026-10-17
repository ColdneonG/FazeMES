package cn.iocoder.yudao.module.system.controller.admin.online.vo;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.*;
@Data @EqualsAndHashCode(callSuper=true)
public class OnlineSessionPageReqVO extends PageParam {
    private Long userId;
    private String clientId;
}
