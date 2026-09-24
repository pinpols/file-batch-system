package io.github.pinpols.batch.console.domain.notification.service;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.console.support.http.OkHttpConsoleExternalHttpTransport;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Slack 通知发送器")
class SlackNotificationSenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("ta", "JOB_FAILED", "alert", "c1", Instant.EPOCH, null);
    return new NotificationMessage("ta", "slack-ops", "SLACK", configJson, payload, "{\"k\":1}");
  }

  @Test
  void shouldSupportSlackCaseInsensitive() {
    SlackNotificationSender sender = new SlackNotificationSender(objectMapper, failOnRequest());

    assertThat(sender.supports("SLACK")).isTrue();
    assertThat(sender.supports("slack")).isTrue();
    assertThat(sender.supports("WEBHOOK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  void shouldFailWithoutGoingNetwork_whenUrlMissing() {
    SlackNotificationSender sender = new SlackNotificationSender(objectMapper, failOnRequest());

    WebhookDeliveryResult result = sender.send(message("{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing slack url");
  }

  @Test
  void shouldReturnOk_whenBodyIsOk() {
    AtomicReference<OutboundHttpRequest> captured = new AtomicReference<>();
    SlackNotificationSender sender = new SlackNotificationSender(objectMapper, request -> {
      captured.set(request);
      return new OutboundHttpResponse(200, "ok");
    });

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://hooks.slack.com/services/XXX\"}"));

    assertThat(result.success()).isTrue();
    assertThat(captured.get().body()).contains("\"text\"").contains("JOB_FAILED");
    assertThat(captured.get().addressPolicy()).isEqualTo(OutboundAddressPolicy.GUARDED);
  }

  @Test
  void shouldFail_whenBodyNotOk() {
    SlackNotificationSender sender = new SlackNotificationSender(
        objectMapper, request -> new OutboundHttpResponse(400, "invalid_payload"));

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://hooks.slack.com/services/XXX\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(400);
    assertThat(result.errorSummary()).isEqualTo("invalid_payload");
  }

  @Test
  void shouldBlock_whenHostResolvesToInternalAddress() {
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://localhost/services/XXX\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }

  @Test
  void shouldBlock_whenUrlIsLiteralInternalIp() {
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }
}
