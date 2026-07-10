package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Slack 通知发送器")
class SlackNotificationSenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SsrfGuardedDns ssrfGuardedDns =
      new SsrfGuardedDns(mock(BatchSecurityProperties.class));

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("ta", "JOB_FAILED", "alert", "c1", Instant.EPOCH, null);
    return new NotificationMessage("ta", "slack-ops", "SLACK", configJson, payload, "{\"k\":1}");
  }

  @Test
  @DisplayName("supports 大小写不敏感匹配 SLACK，其余渠道不接")
  void shouldSupportSlackCaseInsensitive() {
    // arrange
    SlackNotificationSender sender = new SlackNotificationSender(objectMapper, ssrfGuardedDns);

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
        new SlackNotificationSender(objectMapper, ssrfGuardedDns) {
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
        new SlackNotificationSender(objectMapper, ssrfGuardedDns) {
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
        new SlackNotificationSender(objectMapper, ssrfGuardedDns) {
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

  @Test
  @DisplayName("SSRF: 主机名解析到内网回环 -> 建连前被 guard 拦, 不走网络")
  void shouldBlock_whenHostResolvesToInternalAddress() {
    // arrange: postJson 被调用即失败, 证明 guard 在建连前短路
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper, ssrfGuardedDns) {
          @Override
          protected SlackResponse postJson(String url, String body) {
            throw new AssertionError("must not connect to internal address");
          }
        };

    // act
    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://localhost/services/XXX\"}"));

    // assert: BlockedAddressException 折叠为 failure(等价 restricted network range)
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }

  @Test
  @DisplayName("SSRF: 字面量内网/元数据 IP -> 被 guard 兜底拦(OkHttp 对字面量 IP 短路不走 Dns)")
  void shouldBlock_whenUrlIsLiteralInternalIp() {
    // arrange
    SlackNotificationSender sender =
        new SlackNotificationSender(objectMapper, ssrfGuardedDns) {
          @Override
          protected SlackResponse postJson(String url, String body) {
            throw new AssertionError("must not connect to metadata IP");
          }
        };

    // act
    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }
}
