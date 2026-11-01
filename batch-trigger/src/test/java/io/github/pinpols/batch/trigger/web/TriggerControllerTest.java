package io.github.pinpols.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import io.github.pinpols.batch.trigger.config.TriggerApiAdmissionGuard;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchTarget;
import io.github.pinpols.batch.trigger.domain.ExternalEventTargetStatus;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.service.TriggerApiApplicationService;
import io.github.pinpols.batch.trigger.service.TriggerLaunchAdmissionService;
import io.github.pinpols.batch.trigger.service.TriggerRequestPolicy;
import io.github.pinpols.batch.trigger.service.TriggerService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger REST 入口:启动/启动状态查询/补跑审批的入参校验与请求标识生成")
class TriggerControllerTest {

  @Mock
  private TriggerService triggerService;

  @Mock
  private TriggerGracefulShutdown triggerGracefulShutdown;

  @Mock
  private TriggerApiKeyVerifier apiKeyVerifier;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(
            new TriggerController(new TriggerApiApplicationService(
                triggerService,
                new TriggerDrainService(triggerGracefulShutdown),
                new TriggerLaunchAdmissionService(
                    new TriggerApiAdmissionGuard(new TriggerRuntimeProperties()),
                    new TriggerDrainService(triggerGracefulShutdown)),
                new TriggerRequestPolicy(apiKeyVerifier))))
        .setControllerAdvice(TriggerApiExceptionHandler.forStandaloneTest())
        .setMessageConverters(new JacksonJsonHttpMessageConverter())
        .build();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
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
                      "workflowDefinitionId": 41,
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
  @DisplayName("外部事件来源与事件 ID 通过 HTTP 请求绑定并交给 Trigger 服务")
  void shouldBindExternalEventSourceIdentityFromHttpRequest() throws Exception {
    when(triggerService.launch(any())).thenReturn(new LaunchResponse("req-event", "trace-event"));

    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "event-key")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "workflowDefinitionId": 41,
                      "bizDate": "2026-03-27",
                      "triggerType": "EVENT",
                      "externalEventCode": "PAYMENT_SETTLED",
                      "externalEventSource": "billing",
                      "externalEventId": "payment-17"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<TriggerLaunchCommand> captor =
        ArgumentCaptor.forClass(TriggerLaunchCommand.class);
    verify(triggerService).launch(captor.capture());
    assertThat(captor.getValue().request().externalEventSource()).isEqualTo("billing");
    assertThat(captor.getValue().request().externalEventId()).isEqualTo("payment-17");
  }

  @Test
  @DisplayName("API Key principal 只能以自身 source 和 tenant 启动 EVENT")
  void shouldBindExternalEventLaunchToAuthenticatedSource() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t1", "billing")).thenReturn(true);
    when(triggerService.launch(any())).thenReturn(new LaunchResponse("req-event", "trace-event"));

    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "event-key")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "workflowDefinitionId": 41,
                      "bizDate": "2026-03-27",
                      "triggerType": "EVENT",
                      "externalEventCode": "PAYMENT_SETTLED",
                      "externalEventSource": "billing",
                      "externalEventId": "payment-17"
                    }
                    """))
        .andExpect(status().isOk());

    verify(apiKeyVerifier).authorizesExternalEvent(principal, "t1", "billing");
    verify(triggerService).launch(any(TriggerLaunchCommand.class));
  }

  @Test
  @DisplayName("伪造来源时即使处于排水状态也先拒绝授权")
  void shouldRejectExternalEventIdentityNotBoundToApiKey() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t1", "forged-source"))
        .thenReturn(false);
    // 授权应先拒绝，因此排水状态桩可能不被访问。
    lenient().when(triggerGracefulShutdown.isDraining()).thenReturn(true);

    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "forged-event-key")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "workflowDefinitionId": 41,
                      "bizDate": "2026-03-27",
                      "triggerType": "EVENT",
                      "externalEventCode": "PAYMENT_SETTLED",
                      "externalEventSource": "forged-source",
                      "externalEventId": "payment-17"
                    }
                    """))
        .andExpect(status().isForbidden());

    verify(triggerService, never()).launch(any());
    verifyNoInteractions(triggerGracefulShutdown);
  }

  @Test
  @DisplayName("外部来源 API Key 查询状态时按 tenant 和 source 隔离")
  void shouldScopeExternalEventStatusByAuthenticatedSource() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t1", "billing")).thenReturn(true);
    when(triggerService.findLaunchStatus("t1", "event-key", "billing"))
        .thenReturn(
            new TriggerLaunchStatus("req-event", "trace-event", "LAUNCHED", null, null, null));

    mockMvc
        .perform(get("/api/triggers/launch/status")
            .queryParam("tenantId", "t1")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "event-key"))
        .andExpect(status().isOk());

    verify(triggerService).findLaunchStatus("t1", "event-key", "billing");
  }

  @Test
  @DisplayName("外部来源 API Key 不能查询其他租户状态")
  void shouldRejectExternalEventStatusForAnotherTenant() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t2", "billing")).thenReturn(false);

    mockMvc
        .perform(get("/api/triggers/launch/status")
            .queryParam("tenantId", "t2")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "event-key"))
        .andExpect(status().isForbidden());

    verify(triggerService, never()).findLaunchStatus(any(), any(), any());
  }

  @Test
  @DisplayName("外部来源事件接纳只按 API Key 来源路由并返回冻结目标")
  void shouldLaunchExternalEventForAuthenticatedSource() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t1", "billing")).thenReturn(true);
    when(triggerService.launchExternalEvent(any()))
        .thenReturn(new ExternalEventLaunchResponse(
            "delivery-1",
            "evt-1",
            "trace-1",
            List.of(new ExternalEventLaunchTarget(21L, "req-1"))));

    mockMvc
        .perform(post("/api/triggers/events")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-event-1")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "t1",
                  "externalEventCode": "PAYMENT_SETTLED",
                  "externalEventId": "evt-1",
                  "bizDate": "2026-03-27",
                  "params": {"paymentId": "p-1"}
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requestId").value("delivery-1"))
        .andExpect(jsonPath("$.data.targets[0].workflowDefinitionId").value(21))
        .andExpect(jsonPath("$.data.targets[0].requestId").value("req-1"));

    var command = ArgumentCaptor.forClass(ExternalEventLaunchCommand.class);
    verify(triggerService).launchExternalEvent(command.capture());
    assertThat(command.getValue().eventSource()).isEqualTo("billing");
    assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-event-1");
    assertThat(command.getValue().request().externalEventId()).isEqualTo("evt-1");
  }

  @Test
  @DisplayName("外部事件状态按调用方幂等键聚合所有 Workflow 目标")
  void shouldReturnExternalEventStatusByIdempotencyKey() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE"))));
    when(apiKeyVerifier.authorizesExternalEvent(principal, "t1", "billing")).thenReturn(true);
    when(triggerService.findExternalEventStatus("t1", "billing", "idem-event-1"))
        .thenReturn(new ExternalEventLaunchStatus(
            "delivery-1",
            "billing",
            "evt-1",
            "PAYMENT_SETTLED",
            LocalDate.of(2026, 3, 27),
            "trace-1",
            List.of(new ExternalEventTargetStatus(
                21L, "WF_A", "req-1", "trace-1", "ACCEPTED", null, null, null, null, null)),
            null));

    mockMvc
        .perform(get("/api/triggers/events/status")
            .queryParam("tenantId", "t1")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-event-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventId").value("evt-1"))
        .andExpect(jsonPath("$.data.targets[0].workflowDefinitionId").value(21));

    verify(triggerService).findExternalEventStatus("t1", "billing", "idem-event-1");
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
  @DisplayName("启动入参租户无效时返回 400 校验错误,且不调用 TriggerService")
  void shouldReturnValidationErrorWhenRequiredFieldsAreMissing() throws Exception {
    mockMvc
        .perform(post("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "",
                      "workflowDefinitionId": 41,
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
                      "workflowDefinitionId": 41,
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
