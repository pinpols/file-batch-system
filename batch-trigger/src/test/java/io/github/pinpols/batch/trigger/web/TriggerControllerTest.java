package io.github.pinpols.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.trigger.config.TriggerApiAdmissionGuard;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.service.TriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger REST 入口:启动/启动状态查询/补跑审批的入参校验与请求标识生成")
class TriggerControllerTest {

  @Mock
  private TriggerService triggerService;

  @Mock
  private TriggerGracefulShutdown triggerGracefulShutdown;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TriggerController(
            triggerService,
            triggerGracefulShutdown,
            new TriggerApiAdmissionGuard(new TriggerRuntimeProperties())))
        .setControllerAdvice(TriggerApiExceptionHandler.forStandaloneTest())
        .setMessageConverters(new JacksonJsonHttpMessageConverter())
        .build();
  }

  @Test
  @DisplayName("启动请求未带 requestId/traceId 时,服务端按幂等键生成 req- 前缀请求号与 32 位 traceId")
  void shouldGenerateRequestAndTraceIdsWhenHeadersAreMissing() throws Exception {
    when(triggerService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-response"));

    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "jobCode": "IMPORT_JOB",
                      "bizDate": "2026-03-27",
                      "triggerType": "API"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.instanceNo").value("inst-001"));

    ArgumentCaptor<TriggerLaunchCommand> captor =
        ArgumentCaptor.forClass(TriggerLaunchCommand.class);
    verify(triggerService).launch(captor.capture());
    TriggerLaunchCommand command = captor.getValue();
    assertThat(command.idempotencyKey()).isEqualTo("idem-001");
    assertThat(command.requestId()).startsWith("req-");
    assertThat(command.traceId()).hasSize(32);
  }

  @Test
  @DisplayName("按租户与幂等键查询启动状态,返回请求号/请求状态/关联作业实例号与实例状态")
  void shouldReturnLaunchStatusByTenantAndIdempotencyKey() throws Exception {
    when(triggerService.findLaunchStatus("t1", "idem-status"))
        .thenReturn(
            new TriggerLaunchStatus("req-001", "trace-001", "LAUNCHED", 42L, "SUCCESS", null));

    mockMvc
        .perform(get("/api/triggers/launch/status")
            .queryParam("tenantId", "t1")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.requestId").value("req-001"))
        .andExpect(jsonPath("$.data.requestStatus").value("LAUNCHED"))
        .andExpect(jsonPath("$.data.relatedJobInstanceId").value(42))
        .andExpect(jsonPath("$.data.instanceStatus").value("SUCCESS"));
  }

  @Test
  @DisplayName("启动入参缺少 jobCode 或 tenantId 为空时返回 400 校验错误,且不调用 TriggerService")
  void shouldReturnValidationErrorWhenRequiredFieldsAreMissing() throws Exception {
    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "",
                      "bizDate": "2026-03-27"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(content().string(containsString("tenantId is required")));

    verifyNoInteractions(triggerService);
  }

  @Test
  @DisplayName("缺少幂等键请求头时以 400 MISSING_IDEMPOTENCY_KEY 拒绝,不进入 TriggerService")
  void shouldReturnMissingIdempotencyKeyWhenHeaderIsAbsent() throws Exception {
    mockMvc
        .perform(post("/api/triggers/launch").contentType(APPLICATION_JSON).content("""
                    {
                      "tenantId": "t1",
                      "jobCode": "IMPORT_JOB",
                      "bizDate": "2026-03-27",
                      "triggerType": "API"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(triggerService);
  }

  @Test
  @DisplayName("补跑审批入参合法时放行,返回审批生成的实例编号并调用 TriggerService")
  void shouldApproveCatchUpWhenPayloadIsValid() throws Exception {
    when(triggerService.approvePendingCatchUp(any()))
        .thenReturn(new LaunchResponse("inst-cu", "trace-cu"));

    mockMvc
        .perform(post("/api/triggers/catch-up/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cu-1")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "requestId": "req-cu-1",
                      "reason": "approved"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.instanceNo").value("inst-cu"));

    verify(triggerService).approvePendingCatchUp(any());
  }

  @Test
  @DisplayName("补跑审批租户名为空时返回 400 校验错误(must not be blank),且不调用 TriggerService")
  void shouldReturnValidationErrorWhenCatchUpApprovalTenantIsMissing() throws Exception {
    mockMvc
        .perform(post("/api/triggers/catch-up/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-003")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "",
                      "requestId": "req-001",
                      "reason": "ok"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(content().string(containsString("must not be blank")));

    verifyNoInteractions(triggerService);
  }
}
