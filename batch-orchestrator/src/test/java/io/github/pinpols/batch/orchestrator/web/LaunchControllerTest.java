package io.github.pinpols.batch.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.config.InternalAuthFilter;
import io.github.pinpols.batch.orchestrator.controller.LaunchController;
import io.github.pinpols.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LaunchControllerTest {

  @Mock private LaunchService launchService;
  @Mock private TenantActionRateLimiter tenantActionRateLimiter;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LaunchApplicationService launchApplicationService =
        new LaunchApplicationService(launchService, tenantActionRateLimiter);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new LaunchController(launchApplicationService, gracefulShutdown))
            .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
            .build();
  }

  @Test
  void shouldReturnLaunchResponseOnSuccess() throws Exception {
    when(tenantActionRateLimiter.tryConsume(any(), any())).thenReturn(true);
    when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-001"));

    mockMvc
        .perform(
            post("/internal/orchestrator/launch")
                .contentType(APPLICATION_JSON)
                .content(
                    """
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
  void shouldRejectWhenApiKeyResolvedTenantMismatchesBody() throws Exception {
    // arrange: filter 解析出 API-Key 真实租户 t-real,但 body 声明的是 t-fake
    // act + assert: 租户边界守卫拒绝,不应落到 launch
    mockMvc
        .perform(
            post("/internal/orchestrator/launch")
                .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "t-real")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": "t-fake",
                      "jobCode": "IMPORT_JOB",
                      "bizDate": "2026-03-27",
                      "triggerType": "API",
                      "requestId": "req-001",
                      "traceId": "trace-001",
                      "params": {}
                    }
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("error.common.tenant_id_mismatch"));
  }

  @Test
  void shouldUseApiKeyResolvedTenantWhenBodyTenantBlank() throws Exception {
    // arrange: API-Key 解析出 t-real,body 未声明 tenantId
    when(tenantActionRateLimiter.tryConsume(any(), any())).thenReturn(true);
    when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-001"));

    // act: launch 应使用解析出的 t-real,而非 body 里的 null
    mockMvc
        .perform(
            post("/internal/orchestrator/launch")
                .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "t-real")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "jobCode": "IMPORT_JOB",
                      "bizDate": "2026-03-27",
                      "triggerType": "API",
                      "requestId": "req-001",
                      "traceId": "trace-001",
                      "params": {}
                    }
                    """))
        .andExpect(status().isOk());

    // assert: 下游收到的 tenantId 已被守卫改写为 t-real
    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("t-real");
  }

  @Test
  void shouldMapBizExceptionToCommonResponseFailure() throws Exception {
    when(tenantActionRateLimiter.tryConsume(any(), any())).thenReturn(true);
    when(launchService.launch(any()))
        .thenThrow(
            BizException.of(
                ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument", "bad request"));

    mockMvc
        .perform(post("/internal/orchestrator/launch").contentType(APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ResultCode.INVALID_ARGUMENT.name()))
        .andExpect(jsonPath("$.message").value("error.common.invalid_argument"));
  }
}
