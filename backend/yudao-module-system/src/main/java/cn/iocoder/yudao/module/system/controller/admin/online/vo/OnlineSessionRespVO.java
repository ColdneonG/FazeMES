package cn.iocoder.yudao.module.system.controller.admin.online.vo;
import lombok.Data;
import java.time.LocalDateTime;
/** Session identity is an internal numeric id; neither access nor refresh credentials leave the server. */
@Data
public class OnlineSessionRespVO {
    private Long id;
    private Long userId;
    private String username;
    private String nickname;
    private String clientId;
    private LocalDateTime createTime;
    private LocalDateTime expiresTime;
}
