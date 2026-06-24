package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
  @DisplayName("supports 大小写不敏感匹配 SLACK，其余渠道不接")
  void shouldSupportSlackCaseInsensitive() {
    // arrange
    SlackNotificationSender sender = new SlackNotificationSender(objectMapper);

    // act / assert
    assertThat(sender.supports("SLACK")).isTrue();
    assertThat(sender.supports("slack")).isTrue();
    assertThat(sender.supports("WEBHOOK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  @DisplayName("缺 url -> failure 且不走网络")
  void shouldFailWithoutGoingNetwork_whenUrlMissing() {
    // arrange: postJson 若被调用即让测试失败
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper) {
          @Override
          protected SlackResponse postJson(String url, String body) {
            throw new AssertionError("must not call network when url missing");
          }
        };

    // act
    WebhookDeliveryResult result = sender.send(message("{}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing slack url");
  }

  @Test
  @DisplayName("HTTP 200 且 body==ok -> ok，并 POST {\"text\":...}")
  void shouldReturnOk_whenBodyIsOk() {
    // arrange
    AtomicReference<String> captured = new AtomicReference<>();
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper) {
          @Override
          protected SlackResponse postJson(String url, String body) {
            captured.set(body);
            return new SlackResponse(200, "ok");
          }
        };

    // act
    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://hooks.slack.com/services/XXX\"}"));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isNull();
    assertThat(captured.get()).contains("\"text\"").contains("JOB_FAILED");
  }

  @Test
  @DisplayName("非 ok 响应体 -> failure 带状态码与截断响应体")
  void shouldFail_whenBodyNotOk() {
    // arrange
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper) {
          @Override
          protected SlackResponse postJson(String url, String body) {
            return new SlackResponse(400, "invalid_payload");
          }
        };

    // act
    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://hooks.slack.com/services/XXX\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(400);
    assertThat(result.errorSummary()).isEqualTo("invalid_payload");
  }
}
