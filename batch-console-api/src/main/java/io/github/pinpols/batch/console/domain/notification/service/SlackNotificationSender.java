package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
public class SlackNotificationSender implements NotificationSender {

  /** 摘要文案截断上限，避免把整份 payload 灌进 Slack 消息。 */
  private static final int SUMMARY_MAX_CHARS = 1500;

  /** 失败响应体截断上限，避免超长 body 污染日志 / 结果。 */
  private static final int ERROR_SUMMARY_MAX_CHARS = 500;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public SlackNotificationSender(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public boolean supports(String channelType) {
    return "SLACK".equalsIgnoreCase(channelType);
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
      // SSRF/rebinding 防护:租户可配 url,投递前二次解析校验 IP 不落内网/回环/链路本地。
      DnsResolveGuard.resolveAndValidate(URI.create(url).getHost());
      SlackResponse response = postJson(url, body);
      int status = response.status();
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
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn(
          "[slack] delivery interrupted tenant={} channel={}",
          message.tenantId(),
          message.channelCode());
      return WebhookDeliveryResult.failure(null, ie.getClass().getSimpleName());
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

  /** POST JSON 单次响应（HTTP 状态码 + 响应体），便于单测预置覆盖。 */
  protected record SlackResponse(int status, String body) {}

  /** 抽出便于单测覆盖：POST JSON 到 url，返回状态码 + 响应体。 */
  protected SlackResponse postJson(String url, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new SlackResponse(response.statusCode(), response.body());
  }
}
