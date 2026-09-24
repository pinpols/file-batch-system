package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.console.support.http.ConsoleOutboundTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Slack Incoming Webhook 通知发送器（{@link NotificationSender} SPI 实现，channelType = SLACK）。
 *
 * <p>从 {@code notification_channel.config_json} 取 {@code url}（Slack incoming webhook）；POST {@code
 * application/json} body {@code {"text": <摘要>}}。Slack 成功语义特殊：HTTP 200 且响应体为纯文本 {@code
 * ok}，否则视为失败（响应体含 {@code invalid_payload} / {@code no_service} 等错误说明）。
 *
 * <p>无状态、线程安全；所有失败折叠成 {@link WebhookDeliveryResult#failure}，不抛异常。日志净化（不打 url）。
 */
@Slf4j
@Component
@SuppressWarnings("java:S2583")
public class SlackNotificationSender implements NotificationSender {

  /** 摘要文案截断上限，避免把整份 payload 灌进 Slack 消息。 */
  private static final int SUMMARY_MAX_CHARS = 1500;

  /** 失败响应体截断上限，避免超长 body 污染日志 / 结果。 */
  private static final int ERROR_SUMMARY_MAX_CHARS = 500;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

  private final ObjectMapper objectMapper;
  private final OutboundHttpTransport httpTransport;

  public SlackNotificationSender(
      ObjectMapper objectMapper, @ConsoleOutboundTransport OutboundHttpTransport httpTransport) {
    this.objectMapper = objectMapper;
    this.httpTransport = httpTransport;
  }

  @Override
  public String channelType() {
    return "SLACK";
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    String url = resolveUrl(message.configJson());
    if (url == null || url.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing slack url");
    }
    String body;
    try {
      body = objectMapper.writeValueAsString(Map.of("text", buildSummary(message)));
    } catch (RuntimeException | IOException e) {
      log.warn(
          "[slack] payload serialize failed tenant={} channel={} ex={}",
          message.tenantId(),
          message.channelCode(),
          e.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, e.getClass().getSimpleName());
    }

    try {
      OutboundHttpResponse response = httpTransport.execute(OutboundHttpRequest.post(
          url,
          Map.of(),
          body,
          JSON_MEDIA_TYPE,
          CONNECT_TIMEOUT,
          REQUEST_TIMEOUT,
          OutboundAddressPolicy.GUARDED));
      int status = response.statusCode();
      String responseBody = response.body() == null ? "" : response.body();
      if (status >= 200 && status < 300 && "ok".equals(responseBody.trim())) {
        return WebhookDeliveryResult.ok();
      }
      log.warn(
          "[slack] delivery rejected tenant={} channel={} status={}",
          message.tenantId(),
          message.channelCode(),
          status);
      return WebhookDeliveryResult.failure(status, truncate(responseBody, ERROR_SUMMARY_MAX_CHARS));
    } catch (RuntimeException | IOException e) {
      log.warn(
          "[slack] delivery failed tenant={} channel={} ex={}",
          message.tenantId(),
          message.channelCode(),
          e.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, e.getClass().getSimpleName());
    }
  }

  /** 从 config_json 取 {@code url}；解析失败返回 null（视为缺失，不抛异常）。 */
  private String resolveUrl(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(configJson);
      JsonNode url = node.get("url");
      return url == null || url.isNull() ? null : url.asText();
    } catch (RuntimeException | IOException e) {
      log.warn("[slack] config parse failed ex={}", e.getClass().getSimpleName());
      return null;
    }
  }

  /** 由 eventType + payloadJson 拼成截断后的简洁摘要。 */
  private String buildSummary(NotificationMessage message) {
    String eventType =
        message.payload() == null ? message.channelCode() : message.payload().eventType();
    String detail = message.payloadJson() == null ? "" : message.payloadJson();
    String summary = (eventType == null ? "" : eventType) + " " + detail;
    return truncate(summary.trim(), SUMMARY_MAX_CHARS);
  }

  private String truncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }
}
