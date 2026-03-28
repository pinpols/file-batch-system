package com.example.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.controller.LaunchController;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import com.example.batch.orchestrator.service.LaunchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LaunchControllerTest {

    private final LaunchService launchService = org.mockito.Mockito.mock(LaunchService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LaunchController(launchService))
            .setControllerAdvice(new OrchestratorApiExceptionHandler())
            .build();

    @Test
    void shouldReturnLaunchResponseOnSuccess() throws Exception {
        when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-001"));

        mockMvc.perform(post("/internal/orchestrator/launch")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "t1",
                                  "jobCode": "IMPORT_JOB",
                                  "bizDate": "2026-03-27",
                                  "triggerType": "API",
                                  "requestId": "req-001",
                                  "traceId": "trace-001",
                                  "params": {}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceNo").value("inst-001"))
                .andExpect(jsonPath("$.traceId").value("trace-001"));
    }

    @Test
    void shouldMapBizExceptionToCommonResponseFailure() throws Exception {
        when(launchService.launch(any())).thenThrow(new BizException(ResultCode.INVALID_ARGUMENT, "bad request"));

        mockMvc.perform(post("/internal/orchestrator/launch")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.INVALID_ARGUMENT.name()))
                .andExpect(jsonPath("$.message").value("bad request"));
    }
}

