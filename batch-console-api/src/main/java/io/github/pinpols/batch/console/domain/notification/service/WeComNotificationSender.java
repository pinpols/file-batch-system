package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业微信群机器人通知发送器（{@code channelType=WECOM}，平台枚举 WECOM 即企业微信）。
 *
 * <p>从渠道 {@code config_json} 取群机器人 webhook {@code url}，POST 一条 {@code msgtype=text} 文本消息， 文案由事件类型 +
 * 渲染 JSON 截断拼成简洁摘要。企业微信返回 {@code {"errcode":0,...}} 为成功， 其余 errcode / HTTP 非 2xx / 异常一律折叠成 {@link
 * WebhookDeliveryResult#failure}（不抛、不打 url，日志净化）。
 *
 * <p>无状态、线程安全；外部地址统一通过受 SSRF 保护的 {@link OutboundHttpTransport} 发送。
 */
@Component
@Slf4j
@SuppressWarnings("java:S2583")
public class WeComNotificationSender implements NotificationSender {

  /** 企业微信群机器人 text 内容上限 2048 字节，摘要保守截断到 1500 字符。 */
  private static final int MAX_CONTENT_CHARS = 1500;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

  private final ObjectMapper objectMapper;
  private final OutboundHttpTransport httpTransport;

  public WeComNotificationSender(ObjectMapper objectMapper, OutboundHttpTransport httpTransport) {
    this.objectMapper = objectMapper;
    this.httpTransport = httpTransport;
  }

  @Override
  public String channelType() {
    return "WECOM";
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    String url = resolveUrl(message.configJson());
    if (!Texts.hasText(url)) {
      return WebhookDeliveryResult.failure(null, "missing wecom url");
    }
    String body = buildTextMessage(message);
    try {
      OutboundHttpResponse response = httpTransport.execute(OutboundHttpRequest.post(
          url,
          Map.of(),
          body,
          JSON_MEDIA_TYPE,
          CONNECT_TIMEOUT,
          REQUEST_TIMEOUT,
          OutboundAddressPolicy.GUARDED));
      return interpret(response);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // 净化:不打 url，只记异常类型，避免泄露群机器人密钥
      log.warn(
          "WeCom notification delivery failed: tenantId={} channelCode={} cause={}",
          message.tenantId(),
          message.channelCode(),
          e.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, e.getClass().getSimpleName());
    }
  }

  private String resolveUrl(String configJson) {
    if (!Texts.hasText(configJson)) {
      return null;
    }
    try {
      JsonNode root = objectMapper.readTree(configJson);
      JsonNode urlNode = root.get("url");
      return urlNode == null || urlNode.isNull() ? null : urlNode.asText();
    } catch (Exception e) {
      log.warn("WeCom config_json parse failed: cause={}", e.getClass().getSimpleName());
      return null;
    }
  }

  private String buildTextMessage(NotificationMessage message) {
    String content = summarize(message);
    ObjectNode root = objectMapper.createObjectNode();
    root.put("msgtype", "text");
    ObjectNode text = root.putObject("text");
    text.put("content", content);
    return root.toString();
  }

  private String summarize(NotificationMessage message) {
    StringBuilder sb = new StringBuilder();
    WebhookEventPayload payload = message.payload();
    if (payload != null && Texts.hasText(payload.eventType())) {
      sb.append('[').append(payload.eventType()).append("] ");
    }
    if (Texts.hasText(message.payloadJson())) {
      sb.append(message.payloadJson());
    }
    String content = sb.toString().strip();
    if (content.isEmpty()) {
      content = "batch notification";
    }
    if (content.length() > MAX_CONTENT_CHARS) {
      content = content.substring(0, MAX_CONTENT_CHARS) + "...";
    }
    return content;
  }

  private WebhookDeliveryResult interpret(OutboundHttpResponse response) {
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      return WebhookDeliveryResult.failure(status, "wecom http status=" + status);
    }
    int errcode = parseErrcode(response.body());
    if (errcode == 0) {
      return WebhookDeliveryResult.ok();
    }
    return WebhookDeliveryResult.failure(200, "wecom errcode=" + errcode);
  }

  private int parseErrcode(String body) {
    if (!Texts.hasText(body)) {
      // 无 body 视为非 0，触发 failure 而非误判成功
      return -1;
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode errcode = root.get("errcode");
      return errcode == null || errcode.isNull() ? -1 : errcode.asInt(-1);
    } catch (Exception e) {
      log.warn("WeCom response parse failed: cause={}", e.getClass().getSimpleName());
      return -1;
    }
  }
}
