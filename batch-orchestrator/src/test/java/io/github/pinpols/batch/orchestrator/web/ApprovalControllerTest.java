package io.github.pinpols.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.orchestrator.controller.ApprovalController;
import io.github.pinpols.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批接口的请求处理与异常映射:提交成功返回审批单号,缺少租户参数时兜底为系统错误,业务异常映射为对应状态码与消息键")
class ApprovalControllerTest {

  @Mock
  private ApprovalWorkflowService approvalWorkflowService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvalWorkflowService))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();
  }

  @Test
  @DisplayName("提交审批请求成功时返回受理结果中的审批单号")
  void shouldSubmitAndReturnApprovalNo() throws Exception {
    when(approvalWorkflowService.submit(any())).thenReturn("appr-001");

    mockMvc
        .perform(post("/internal/approvals").contentType(APPLICATION_JSON).content("""
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
  @DisplayName("查询审批单未携带租户参数时由兜底异常处理返回系统错误码")
  void shouldReturn400WhenTenantIdParamMissingOnGet() throws Exception {
    mockMvc
        .perform(get("/internal/approvals/appr-001"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.name()));
  }

  @Test
  @DisplayName("审批单不存在时业务异常映射为未找到状态码并回传错误消息键")
  void shouldMapBizExceptionToCommonResponseFailure() throws Exception {
    when(approvalWorkflowService.get("t1", "appr-404"))
        .thenThrow(BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", "not found"));

    mockMvc
        .perform(get("/internal/approvals/appr-404").param("tenantId", "t1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.name()))
        .andExpect(jsonPath("$.message").value("error.common.not_found"));
  }
}
