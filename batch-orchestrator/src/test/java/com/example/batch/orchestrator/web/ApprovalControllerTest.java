package com.example.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.service.ApprovalWorkflowService;
import com.example.batch.orchestrator.controller.ApprovalController;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalControllerTest {

    private final ApprovalWorkflowService approvalWorkflowService = org.mockito.Mockito.mock(ApprovalWorkflowService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvalWorkflowService))
            .setControllerAdvice(new OrchestratorApiExceptionHandler())
            .build();

    @Test
    void shouldSubmitAndReturnApprovalNo() throws Exception {
        when(approvalWorkflowService.submit(any())).thenReturn("appr-001");

        mockMvc.perform(post("/internal/approvals")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "t1",
                                  "approvalType": "CATCH_UP",
                                  "actionType": "APPROVE",
                                  "targetType": "TRIGGER_REQUEST",
                                  "targetId": "req-001",
                                  "payloadJson": "{}",
                                  "requesterId": "u1",
                                  "sourceTraceId": "trace-001",
                                  "sourceIdempotencyKey": "idem-001",
                                  "approvalReason": "ok"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalNo").value("appr-001"));
    }

    @Test
    void shouldReturn400WhenTenantIdParamMissingOnGet() throws Exception {
        mockMvc.perform(get("/internal/approvals/appr-001"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.name()));
    }

    @Test
    void shouldMapBizExceptionToCommonResponseFailure() throws Exception {
        when(approvalWorkflowService.get(eq("t1"), eq("appr-404")))
                .thenThrow(new BizException(ResultCode.NOT_FOUND, "not found"));

        mockMvc.perform(get("/internal/approvals/appr-404").param("tenantId", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.name()))
                .andExpect(jsonPath("$.message").value("not found"));
    }
}

