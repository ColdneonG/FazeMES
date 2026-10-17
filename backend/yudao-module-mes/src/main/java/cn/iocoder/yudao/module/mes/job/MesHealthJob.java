package cn.iocoder.yudao.module.mes.job;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 可由任务管理调度的只读健康检查，执行结果写入既有任务日志。 */
@Component("fazemesHealthJob")
public class MesHealthJob implements JobHandler {
    @Override
    public String execute(String param) {
        return "FazeMES scheduler OK " + LocalDateTime.now();
    }
}
