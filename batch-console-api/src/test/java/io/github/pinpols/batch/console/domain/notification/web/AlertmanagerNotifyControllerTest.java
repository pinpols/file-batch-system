package io.github.pinpols.batch.console.domain.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    properties.setEnabled(false);
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive("Bearer s3cr3t", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(e ->
            assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(notifyService, never()).deliver(any(), any());
  }
}
