package cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "人工排产资源及时间冲突检查，结果为检查时快照")
public class MesProTaskScheduleCheckRespVO {
    private Long workstationId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean resourceAvailable;
    private List<String> blockingReasons = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<Predecessor> predecessors = new ArrayList<>();

    @Data
    public static class Predecessor {
        private Long id;
        private String code;
        private LocalDateTime endTime;
    }
}
