package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("企业微信群机器人通知发送器")
class WeComNotificationSenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SsrfGuardedDns ssrfGuardedDns =
      new SsrfGuardedDns(mock(BatchSecurityProperties.class));

  /** 记录 postJson 是否被调用 + 收到的 body，断言「缺 url 不走网络」。 */
  private AtomicReference<String> capturedBody;

  /** 可配置返回的桩：覆盖 postJson 返回预置 JSON，不走真实网络。 */
  private WeComNotificationSender newSender(int statusCode, String responseBody) {
    return new WeComNotificationSender(objectMapper, ssrfGuardedDns) {
      @Override
      protected WeComHttpResponse postJson(String url, String body) {
        capturedBody.set(body);
        return new WeComHttpResponse(statusCode, responseBody);
      }
    };
  }

  /** postJson 被调用即失败的桩：证明 SSRF guard 在建连前短路。 */
  private WeComNotificationSender newSenderThatMustNotConnect() {
    return new WeComNotificationSender(objectMapper, ssrfGuardedDns) {
      @Override
      protected WeComHttpResponse postJson(String url, String body) {
        throw new AssertionError("must not connect to restricted address");
      }
    };
  }

  @BeforeEach
  void setUp() {
    capturedBody = new AtomicReference<>(null);
  }

  private NotificationMessage messageWith(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload(
            "t1", "JOB_FAILED", "jobs", "cursor-1", Instant.parse("2026-06-24T00:00:00Z"), null);
    return new NotificationMessage(
        "t1", "wecom-bot", "WECOM", configJson, payload, "{\"jobId\":42}");
  }

  @Test
  @DisplayName("supports 仅匹配 WECOM（规范渠道值，对齐 DDL/白名单/DictEnum，大小写不敏感）")
  void shouldSupportWecomChannelTypeCaseInsensitive() {
    // arrange
    WeComNotificationSender sender = newSender(200, "{\"errcode\":0}");

    // act / assert
    assertThat(sender.supports("WECOM")).isTrue();
    assertThat(sender.supports("wecom")).isTrue();
    // 历史误值 WECHAT 不再匹配(渠道类型的规范值统一为 WECOM)。
    assertThat(sender.supports("WECHAT")).isFalse();
    assertThat(sender.supports("DINGTALK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  @DisplayName("缺 url 直接 failure，不走网络")
  void shouldFailWithoutHittingNetwork_whenUrlMissing() {
    // arrange
    WeComNotificationSender sender = newSender(200, "{\"errcode\":0}");

    // act
    WebhookDeliveryResult result = sender.send(messageWith("{\"foo\":\"bar\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing wecom url");
    assertThat(capturedBody.get()).isNull();
  }

  @Test
  @DisplayName("errcode=0 → ok，且 body 为 text 消息")
  void shouldReturnOk_whenErrcodeZero() {
    // arrange
    WeComNotificationSender sender = newSender(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");

    // act
    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isNull();
    assertThat(capturedBody.get()).contains("\"msgtype\":\"text\"").contains("JOB_FAILED");
  }

  @Test
  @DisplayName("errcode 非 0 → failure(200, errcode)")
  void shouldReturnFailure_whenErrcodeNonZero() {
    // arrange
    WeComNotificationSender sender =
        newSender(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}");

    // act
    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("wecom errcode=93000");
  }

  @Test
  @DisplayName("SSRF: 主机名解析到内网回环 → 建连前被 guard 拦, 不走网络")
  void shouldBlock_whenHostResolvesToInternalAddress() {
    // arrange
    WeComNotificationSender sender = newSenderThatMustNotConnect();

    // act
    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://localhost/robot?key=x\"}"));

    // assert: BlockedAddressException 折叠为 failure(等价 restricted network range)
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
    assertThat(capturedBody.get()).isNull();
  }

  @Test
  @DisplayName("SSRF: 字面量内网/元数据 IP → 被 guard 兜底拦(OkHttp 对字面量 IP 短路不走 Dns)")
  void shouldBlock_whenUrlIsLiteralInternalIp() {
    // arrange
    WeComNotificationSender sender = newSenderThatMustNotConnect();

    // act
    WebhookDeliveryResult result =
        sender.send(messageWith("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("BlockedAddressException");
    assertThat(capturedBody.get()).isNull();
  }
}
