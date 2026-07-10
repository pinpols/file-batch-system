package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 企业微信群机器人通知发送器（{@code channelType=WECOM}，平台枚举 WECOM 即企业微信）。
 *
 * <p>从渠道 {@code config_json} 取群机器人 webhook {@code url}，POST 一条 {@code msgtype=text} 文本消息， 文案由事件类型 +
 * 渲染 JSON 截断拼成简洁摘要。企业微信返回 {@code {"errcode":0,...}} 为成功， 其余 errcode / HTTP 非 2xx / 异常一律折叠成 {@link
 * WebhookDeliveryResult#failure}（不抛、不打 url，日志净化）。
 *
 * <p>无状态、线程安全（单例 bean，共享 {@link OkHttpClient}）。参考 captcha 远程校验器范式： HTTP POST 抽 {@code protected}
 * 方法便于单测打桩。
 */
@Component
@Slf4j
public class WeComNotificationSender implements NotificationSender {

  /** 企业微信群机器人 text 内容上限 2048 字节，摘要保守截断到 1500 字符。 */
  private static final int MAX_CONTENT_CHARS = 1500;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** 单次调用总时长上限,兜住慢回调长期占用线程(与 WebhookDispatcher 同款)。 */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final ObjectMapper objectMapper;

  /**
   * OkHttp 客户端内置 {@link SsrfGuardedDns},在建连回调层做 per-request SSRF pin —— 连的就是 guard 校验过的那个 IP, 关闭
   * DNS-rebinding 的 TOCTOU 窗口,TLS 仍按 hostname 校验(SNI/证书不动)。与 WebhookDispatcher / worker-dispatch
   * 同款。
   */
  private final OkHttpClient httpClient;

  public WeComNotificationSender(ObjectMapper objectMapper, SsrfGuardedDns ssrfGuardedDns) {
    this.objectMapper = objectMapper;
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(REQUEST_TIMEOUT)
            .writeTimeout(REQUEST_TIMEOUT)
            .callTimeout(CALL_TIMEOUT)
            .dns(ssrfGuardedDns)
            .build();
  }

  @Override
  public boolean supports(String channelType) {
    return "WECOM".equalsIgnoreCase(channelType);
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    String url = resolveUrl(message.configJson());
    if (!StringUtils.hasText(url)) {
      return WebhookDeliveryResult.failure(null, "missing wecom url");
    }
    String body = buildTextMessage(message);
    try {
      // 字面量 IP 兜底:OkHttp 对字面量 IP 短路不走 SsrfGuardedDns,故这里补一次 guard 拦住 metadata/内网字面量 IP。
      // IM 渠道 url 存前未过 CallbackUrlValidator(仅 WEBHOOK 渠道过),故此兜底不可省。主机名的实连 IP 由 SsrfGuardedDns
      // 在建连回调层 pin(连的就是校验的那个 IP),关闭 rebinding 窗口。
      DnsResolveGuard.resolveAndValidate(URI.create(url).getHost());
      WeComHttpResponse response = postJson(url, body);
      return interpret(response);
    } catch (Exception e) {
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
    if (!StringUtils.hasText(configJson)) {
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
    if (payload != null && StringUtils.hasText(payload.eventType())) {
      sb.append('[').append(payload.eventType()).append("] ");
    }
    if (StringUtils.hasText(message.payloadJson())) {
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

  private WebhookDeliveryResult interpret(WeComHttpResponse response) {
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
    if (!StringUtils.hasText(body)) {
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

  /** HTTP POST 抽出便于单测打桩（覆盖此方法返回预置 JSON，不走真实网络）。 */
  protected WeComHttpResponse postJson(String url, String body) throws Exception {
    Request request = new Request.Builder().url(url).post(RequestBody.create(body, JSON)).build();
    try (Response response = httpClient.newCall(request).execute()) {
      String responseBody = response.body() == null ? "" : response.body().string();
      return new WeComHttpResponse(response.code(), responseBody);
    }
  }

  /** {@link #postJson} 返回的最小响应视图，解耦 OkHttp {@link Response}，便于单测构造。 */
  protected record WeComHttpResponse(int statusCode, String body) {}
}
