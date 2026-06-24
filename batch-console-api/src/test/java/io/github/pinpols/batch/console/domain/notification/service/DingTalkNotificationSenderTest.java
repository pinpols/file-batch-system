package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DingTalkNotificationSenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "JOB_FAILED", "jobs", "c1", Instant.EPOCH, null);
    return new NotificationMessage(
        "tenant-a", "ch-dingtalk", "DINGTALK", configJson, payload, "{\"jobId\":\"J1\"}");
  }

  @Test
  void supportsIsCaseInsensitive() {
    DingTalkNotificationSender sender = new DingTalkNotificationSender(objectMapper);
    assertThat(sender.supports("DINGTALK")).isTrue();
    assertThat(sender.supports("dingtalk")).isTrue();
    assertThat(sender.supports("DingTalk")).isTrue();
    assertThat(sender.supports("WEBHOOK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  void missingUrlFailsWithoutNetworkCall() {
    AtomicReference<Boolean> called = new AtomicReference<>(false);
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper) {
          @Override
          protected String postJson(String url, String body) {
            called.set(true);
            return "{\"errcode\":0}";
          }
        };

    WebhookDeliveryResult result = sender.send(message("{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing dingtalk url");
    assertThat(called.get()).isFalse();
  }

  @Test
  void errcodeZeroIsOk() {
    AtomicReference<String> sentBody = new AtomicReference<>();
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper) {
          @Override
          protected String postJson(String url, String body) {
            sentBody.set(body);
            return "{\"errcode\":0,\"errmsg\":\"ok\"}";
          }
        };

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\"}"));

    assertThat(result.success()).isTrue();
    assertThat(sentBody.get()).contains("\"msgtype\":\"text\"");
    assertThat(sentBody.get()).contains("JOB_FAILED");
  }

  @Test
  void nonZeroErrcodeFails() {
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper) {
          @Override
          protected String postJson(String url, String body) {
            return "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}";
          }
        };

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("dingtalk errcode=310000");
  }

  @Test
  void secretAppendsTimestampAndSignToUrl() {
    AtomicReference<String> sentUrl = new AtomicReference<>();
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper) {
          @Override
          protected long epochMillis() {
            return 1_700_000_000_000L;
          }

          @Override
          protected String postJson(String url, String body) {
            sentUrl.set(url);
            return "{\"errcode\":0}";
          }
        };

    WebhookDeliveryResult result =
        sender.send(
            message(
                "{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\","
                    + "\"secret\":\"SEC123\"}"));

    assertThat(result.success()).isTrue();
    assertThat(sentUrl.get()).contains("timestamp=1700000000000");
    assertThat(sentUrl.get()).contains("&sign=");
    // 加签后的 sign 是 base64+urlencode，非空且不等于明文 secret。
    String sign = sentUrl.get().substring(sentUrl.get().indexOf("&sign=") + "&sign=".length());
    assertThat(sign).isNotBlank().doesNotContain("SEC123");
  }
}
