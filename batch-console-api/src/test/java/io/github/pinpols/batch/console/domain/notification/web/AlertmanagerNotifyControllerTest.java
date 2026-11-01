package io.github.pinpols.batch.console.domain.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("告警接入接口: 令牌鉴权, 开关关闭与投递结论透出")
class AlertmanagerNotifyControllerTest {

  @Mock
  private AlertmanagerNotifyService notifyService;

  private AlertmanagerNotifyProperties properties;
  private AlertmanagerNotifyController controller;

  @BeforeEach
  void setUp() {
    properties = new AlertmanagerNotifyProperties();
    controller = new AlertmanagerNotifyController(properties, notifyService);
  }

  private AlertmanagerWebhookPayload payload() {
    return new AlertmanagerWebhookPayload(
        "4", "gk", 0, "firing", "batch-default", Map.of(), Map.of(), Map.of(), null, List.of());
  }

  @Test
  @DisplayName("携带的令牌与配置一致时接收告警, 并透出投递结论")
  void shouldDeliver_whenBearerTokenMatches() {
    properties.setBearerToken("s3cr3t");
    when(notifyService.deliver(eq("batch-default"), any()))
        .thenReturn(new AmNotifyOutcome("batch-default", "batch-default", true, "SUCCESS", null));

    CommonResponse<AmNotifyOutcome> response =
        controller.receive("Bearer s3cr3t", "batch-default", payload());

    assertThat(response.data().delivered()).isTrue();
    verify(notifyService).deliver(eq("batch-default"), any());
  }

  @Test
  @DisplayName("主动禁用渠道正常接收但不标记送达, 不让上游无效重试")
  void shouldAcceptWithoutDelivery_whenChannelIntentionallyDisabled() {
    properties.setBearerToken("s3cr3t");
    when(notifyService.deliver(eq("batch-default"), any()))
        .thenReturn(AmNotifyOutcome.disabled("batch-default", "batch-default"));

    CommonResponse<AmNotifyOutcome> response =
        controller.receive("Bearer s3cr3t", "batch-default", payload());

    assertThat(response.data().delivered()).isFalse();
    assertThat(response.data().intentionallyDisabled()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "SKIPPED"})
  @DisplayName("渠道失败或尚未配置时返回可重试错误，不能确认告警已经送达")
  void shouldRejectForRetry_whenNotificationWasNotDelivered(String status) {
    properties.setBearerToken("s3cr3t");
    AmNotifyOutcome failed = new AmNotifyOutcome(
        "batch-default", "batch-default", false, status, "delivery unavailable");
    when(notifyService.deliver(eq("batch-default"), any())).thenReturn(failed);

    AlertmanagerWebhookPayload alertPayload = payload();
    assertThatThrownBy(() -> controller.receive("Bearer s3cr3t", "batch-default", alertPayload))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> assertThat(((BizException) exception).getCode())
            .isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
  }

  @Test
  @DisplayName("真实 MVC 异常映射先返回 503，原载荷重试成功后才返回 200")
  void shouldReturnRetryableHttpStatusUntilDeliverySucceeds() throws Exception {
    properties.setBearerToken("s3cr3t");
    AmNotifyOutcome failed = new AmNotifyOutcome(
        "batch-default", "batch-default", false, "FAILED", "provider unavailable");
    AmNotifyOutcome delivered =
        new AmNotifyOutcome("batch-default", "batch-default", true, "SUCCESS", null);
    AlertmanagerWebhookPayload alertPayload = payload();
    when(notifyService.deliver("batch-default", alertPayload)).thenReturn(failed, delivered);
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    ConsoleRequestMetadataResolver metadataResolver =
        new ConsoleRequestMetadataResolver(servletRequest);
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(metadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(exceptionHandler)
        .build();
    String requestJson = JsonUtils.toJson(alertPayload);

    mockMvc
        .perform(post("/internal/am-notify/batch-default")
            .header("Authorization", "Bearer s3cr3t")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isServiceUnavailable());
    mockMvc
        .perform(post("/internal/am-notify/batch-default")
            .header("Authorization", "Bearer s3cr3t")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isOk());
    verify(notifyService, times(2)).deliver("batch-default", alertPayload);
  }

  @Test
  @DisplayName("未携带鉴权头时以未授权拒绝, 不调用投递服务")
  void shouldReject_whenTokenMissing() {
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive(null, "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  @DisplayName("令牌与配置不一致时以未授权拒绝, 不调用投递服务")
  void shouldReject_whenTokenMismatch() {
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive("Bearer nope", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  @DisplayName("服务端未配置令牌时按失败关闭处理, 以未授权拒绝")
  void shouldReject_whenNoTokenConfigured() {
    properties.setBearerToken("  ");

    assertThatThrownBy(() -> controller.receive("Bearer anything", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  @DisplayName("接入开关关闭时以服务不可用拒绝, 不调用投递服务")
  void shouldReject_whenEndpointDisabled() {
    properties.setBearerToken("s3cr3t");
    doThrow(BizException.of(
            ResultCode.SERVICE_UNAVAILABLE,
            "error.common.unauthorized_detail",
            "am-notify disabled"))
        .when(notifyService)
        .assertEnabled();

    assertThatThrownBy(() -> controller.receive("Bearer s3cr3t", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(e ->
            assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(notifyService, never()).deliver(any(), any());
  }
}
