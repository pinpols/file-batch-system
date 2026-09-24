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
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper, failOnRequest());

    assertThat(sender.supports("DINGTALK")).isTrue();
    assertThat(sender.supports("dingtalk")).isTrue();
    assertThat(sender.supports("DingTalk")).isTrue();
    assertThat(sender.supports("WEBHOOK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  void missingUrlFailsWithoutNetworkCall() {
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper, failOnRequest());

    WebhookDeliveryResult result = sender.send(message("{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing dingtalk url");
  }

  @Test
  void errcodeZeroIsOk() {
    AtomicReference<OutboundHttpRequest> captured = new AtomicReference<>();
    DingTalkNotificationSender sender = new DingTalkNotificationSender(objectMapper, request -> {
      captured.set(request);
      return new OutboundHttpResponse(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
    });

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\"}"));

    assertThat(result.success()).isTrue();
    assertThat(captured.get().body()).contains("\"msgtype\":\"text\"").contains("JOB_FAILED");
    assertThat(captured.get().addressPolicy()).isEqualTo(OutboundAddressPolicy.GUARDED);
  }

  @Test
  void nonZeroErrcodeFails() {
    DingTalkNotificationSender sender = new DingTalkNotificationSender(
        objectMapper,
        request -> new OutboundHttpResponse(
            200, "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"));

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("dingtalk errcode=310000");
  }

  @Test
  void secretAppendsTimestampAndSignToUrl() {
    AtomicReference<OutboundHttpRequest> captured = new AtomicReference<>();
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper, request -> {
          captured.set(request);
          return new OutboundHttpResponse(200, "{\"errcode\":0}");
        }) {
          @Override
          protected long epochMillis() {
            return 1_700_000_000_000L;
          }
        };

    WebhookDeliveryResult result = sender.send(message(
        "{\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\",\"secret\":\"SEC123\"}"));

    assertThat(result.success()).isTrue();
    String sentUrl = captured.get().uri().toString();
    assertThat(sentUrl).contains("timestamp=1700000000000").contains("&sign=");
    String sign = sentUrl.substring(sentUrl.indexOf("&sign=") + "&sign=".length());
    assertThat(sign).isNotBlank().doesNotContain("SEC123");
  }

  @Test
  void ssrfHostResolvingToInternalIsBlockedBeforeNetwork() {
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://localhost/robot/send?access_token=t\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }

  @Test
  void ssrfLiteralInternalIpIsBlocked() {
    DingTalkNotificationSender sender =
        new DingTalkNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(message("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }
}
