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
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertmanagerNotifyControllerTest {

  @Mock private AlertmanagerNotifyService notifyService;

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
  void deliversWhenBearerTokenMatches() {
    properties.setBearerToken("s3cr3t");
    when(notifyService.deliver(eq("batch-default"), any()))
        .thenReturn(new AmNotifyOutcome("batch-default", "batch-default", true, "SUCCESS", null));

    CommonResponse<AmNotifyOutcome> response =
        controller.receive("Bearer s3cr3t", "batch-default", payload());

    assertThat(response.data().delivered()).isTrue();
    verify(notifyService).deliver(eq("batch-default"), any());
  }

  @Test
  void rejectsWhenTokenMissing() {
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive(null, "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  void rejectsWhenTokenWrong() {
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive("Bearer nope", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  void failClosedWhenNoTokenConfigured() {
    properties.setBearerToken("  ");

    assertThatThrownBy(() -> controller.receive("Bearer anything", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    verify(notifyService, never()).deliver(any(), any());
  }

  @Test
  void rejectsWithServiceUnavailableWhenDisabled() {
    properties.setEnabled(false);
    properties.setBearerToken("s3cr3t");

    assertThatThrownBy(() -> controller.receive("Bearer s3cr3t", "batch-default", payload()))
        .isInstanceOf(BizException.class)
        .satisfies(
            e ->
                assertThat(((BizException) e).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(notifyService, never()).deliver(any(), any());
  }
}
