package cn.iocoder.yudao.server.controller;

import cn.iocoder.yudao.module.mes.controller.admin.pro.task.MesProTaskController;
import cn.iocoder.yudao.module.mes.controller.admin.pro.task.vo.MesProTaskScheduleCheckRespVO;
import cn.iocoder.yudao.module.mes.service.pro.task.MesProTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise Spring's actual URL selection without starting databases or application jobs. */
class DefaultControllerMesMappingTest {

    private MesProTaskService taskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        taskService = mock(MesProTaskService.class);
        MesProTaskController taskController = new MesProTaskController();
        ReflectionTestUtils.setField(taskController, "taskService", taskService);
        mvc = MockMvcBuilders.standaloneSetup(new DefaultController(), taskController)
                .setCustomHandlerMapping(() -> {
                    RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
                    // The application adds this prefix to module admin controllers.
                    mapping.setPathPrefixes(Map.of("/admin-api", type -> type == MesProTaskController.class));
                    return mapping;
                }).build();
    }

    @Test
    void realScheduleCheckWinsOverMesWildcard() throws Exception {
        when(taskService.checkSchedule(any())).thenReturn(
                new MesProTaskScheduleCheckRespVO().setResourceAvailable(true));

        mvc.perform(post("/admin-api/mes/pro/task/check-schedule")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resourceAvailable").value(true));
        verify(taskService).checkSchedule(any());
    }

    @Test
    void realTaskCreationWinsOverMesWildcard() throws Exception {
        when(taskService.createTask(any())).thenReturn(123L);

        mvc.perform(post("/admin-api/mes/pro/task/create")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(123));
        verify(taskService).createTask(any());
    }

    @Test
    void unknownEndpointReportsItsAddressWithoutClaimingMesIsDisabled() throws Exception {
        mvc.perform(post("/admin-api/mes/pro/task/unknown-action"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg", containsString("/admin-api/mes/pro/task/unknown-action")))
                .andExpect(jsonPath("$.msg", containsString("前后端版本")))
                .andExpect(jsonPath("$.msg", not(containsString("已禁用"))));
        verifyNoInteractions(taskService);
    }

    @Test
    void absentMesControllerAlsoTruthfullyReportsMissingEndpoint() throws Exception {
        MockMvc withoutMes = MockMvcBuilders.standaloneSetup(new DefaultController()).build();

        withoutMes.perform(get("/admin-api/mes/pro/task/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg", containsString("请求的 MES 接口不存在")))
                .andExpect(jsonPath("$.msg", not(containsString("已禁用"))));
    }
}
