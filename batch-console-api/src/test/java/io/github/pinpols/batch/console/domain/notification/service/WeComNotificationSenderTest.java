package io.github.pinpols.batch.console.domain.notification.service;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.console.support.http.OkHttpConsoleExternalHttpTransport;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("企业微信群机器人通知发送器")
class WeComNotificationSenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AtomicReference<OutboundHttpRequest> capturedRequest;

  private WeComNotificationSender newSender(int statusCode, String responseBody) {
    OutboundHttpTransport transport = request -> {
      capturedRequest.set(request);
      return new OutboundHttpResponse(statusCode, responseBody);
    };
    return new WeComNotificationSender(objectMapper, transport);
  }

  @BeforeEach
  void setUp() {
    capturedRequest = new AtomicReference<>();
  }

  private NotificationMessage messageWith(String configJson) {
    WebhookEventPayload payload = new WebhookEventPayload(
        "t1", "JOB_FAILED", "jobs", "cursor-1", Instant.parse("2026-06-24T00:00:00Z"), null);
    return new NotificationMessage(
        "t1", "wecom-bot", "WECOM", configJson, payload, "{\"jobId\":42}");
  }

  @Test
  void shouldSupportWecomChannelTypeCaseInsensitive() {
    WeComNotificationSender sender = newSender(200, "{\"errcode\":0}");

    assertThat(sender.supports("WECOM")).isTrue();
    assertThat(sender.supports("wecom")).isTrue();
    assertThat(sender.supports("WECHAT")).isFalse();
    assertThat(sender.supports("DINGTALK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  void shouldFailWithoutHittingNetwork_whenUrlMissing() {
    WeComNotificationSender sender = new WeComNotificationSender(objectMapper, failOnRequest());

    WebhookDeliveryResult result = sender.send(messageWith("{\"foo\":\"bar\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing wecom url");
    assertThat(capturedRequest.get()).isNull();
  }

  @Test
  void shouldReturnOk_whenErrcodeZero() {
    WeComNotificationSender sender = newSender(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");

    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));

    assertThat(result.success()).isTrue();
    assertThat(capturedRequest.get().body())
        .contains("\"msgtype\":\"text\"")
        .contains("JOB_FAILED");
    assertThat(capturedRequest.get().addressPolicy()).isEqualTo(OutboundAddressPolicy.GUARDED);
  }

  @Test
  void shouldReturnFailure_whenErrcodeNonZero() {
    WeComNotificationSender sender =
        newSender(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}");

    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("wecom errcode=93000");
  }

  @Test
  void shouldBlock_whenHostResolvesToInternalAddress() {
    WeComNotificationSender sender =
        new WeComNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://localhost/robot?key=x\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }

  @Test
  void shouldBlock_whenUrlIsLiteralInternalIp() {
    WeComNotificationSender sender =
        new WeComNotificationSender(objectMapper, new OkHttpConsoleExternalHttpTransport());

    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
  }
}
