package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * 钉钉群机器人通知发送器（{@code channelType == "DINGTALK"}）。
 *
 * <p>从 {@link NotificationMessage#configJson()} 取 {@code url}（机器人 webhook，必填）与可选 {@code
 * secret}（加签密钥）。 有 secret 时按钉钉加签规则给 url 追加 {@code &timestamp=&sign=}；POST {@code application/json}
 * 文本消息， 解析返回的 {@code errcode}：0 为成功，否则折叠成 {@link WebhookDeliveryResult#failure}。
 *
 * <p>无状态、线程安全（单例 bean，共享 {@link OkHttpClient}）；所有失败折叠为 failure 而非抛异常。 日志净化：不打印 secret 与加签后的 url。
 */
@Slf4j
@Component
public class DingTalkNotificationSender implements NotificationSender {

  /** payloadJson 拼入文案前的最大字符数，防止文案过长被钉钉截断或拒绝。 */
  private static final int MAX_PAYLOAD_CHARS = 1000;

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

  public DingTalkNotificationSender(ObjectMapper objectMapper, SsrfGuardedDns ssrfGuardedDns) {
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
    return "DINGTALK".equalsIgnoreCase(channelType);
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    String url;
    String secret;
    try {
      JsonNode config = objectMapper.readTree(message.configJson());
      url = textOrNull(config, "url");
      secret = textOrNull(config, "secret");
    } catch (Exception ex) {
      log.warn(
          "dingtalk config parse failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "invalid dingtalk config");
    }

    if (url == null || url.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing dingtalk url");
    }

    String requestUrl;
    try {
      requestUrl = (secret == null || secret.isBlank()) ? url : signedUrl(url, secret);
    } catch (Exception ex) {
      log.warn(
          "dingtalk sign failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "dingtalk sign failed");
    }

    String body;
    try {
      body = buildBody(message);
    } catch (Exception ex) {
      log.warn(
          "dingtalk body build failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "dingtalk body build failed");
    }

    try {
      // 字面量 IP 兜底:OkHttp 对字面量 IP 短路不走 SsrfGuardedDns,故这里补一次 guard 拦住 metadata/内网字面量 IP。
      // IM 渠道 url 存前未过 CallbackUrlValidator(仅 WEBHOOK 渠道过),故此兜底不可省。主机名的实连 IP 由 SsrfGuardedDns
      // 在建连回调层 pin(连的就是校验的那个 IP),关闭 rebinding 窗口。注:加签在 requestUrl 上,校验用原始 url 的 host。
      DnsResolveGuard.resolveAndValidate(URI.create(url).getHost());
      String response = postJson(requestUrl, body);
      JsonNode node = objectMapper.readTree(response);
      int errcode = node.path("errcode").asInt(-1);
      if (errcode == 0) {
        return WebhookDeliveryResult.ok();
      }
      log.warn("dingtalk rejected channel={} errcode={}", message.channelCode(), errcode);
      return WebhookDeliveryResult.failure(200, "dingtalk errcode=" + errcode);
    } catch (HttpStatusException ex) {
      log.warn("dingtalk http failed channel={} status={}", message.channelCode(), ex.status());
      return WebhookDeliveryResult.failure(ex.status(), "dingtalk http status=" + ex.status());
    } catch (Exception ex) {
      log.warn(
          "dingtalk delivery failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, ex.getClass().getSimpleName());
    }
  }

  /**
   * 按钉钉加签规则追加 timestamp/sign。签名串 = {@code timestamp + "\n" + secret}，HmacSHA256+Base64+urlencode。
   */
  private String signedUrl(String url, String secret) throws Exception {
    long timestamp = epochMillis();
    String stringToSign = timestamp + "\n" + secret;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
    String sign =
        URLEncoder.encode(Base64.getEncoder().encodeToString(digest), StandardCharsets.UTF_8);
    String separator = url.contains("?") ? "&" : "?";
    return url + separator + "timestamp=" + timestamp + "&sign=" + sign;
  }

  /** 构造钉钉 text 消息 body：{@code {"msgtype":"text","text":{"content":<摘要>}}}。 */
  private String buildBody(NotificationMessage message) throws Exception {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("msgtype", "text");
    ObjectNode text = root.putObject("text");
    text.put("content", summarize(message));
    return objectMapper.writeValueAsString(root);
  }

  /** 由 eventType + 截断后的 payloadJson 拼成简洁中文摘要。 */
  private String summarize(NotificationMessage message) {
    String eventType =
        (message.payload() != null && message.payload().eventType() != null)
            ? message.payload().eventType()
            : "UNKNOWN";
    String detail = message.payloadJson() == null ? "" : message.payloadJson();
    if (detail.length() > MAX_PAYLOAD_CHARS) {
      detail = detail.substring(0, MAX_PAYLOAD_CHARS) + "...(truncated)";
    }
    return "【批处理通知】事件类型: " + eventType + "\n详情: " + detail;
  }

  private static String textOrNull(JsonNode config, String field) {
    JsonNode node = config.get(field);
    return (node == null || node.isNull()) ? null : node.asText();
  }

  /** 当前毫秒时间戳，抽出便于单测固定签名。 */
  protected long epochMillis() {
    return System.currentTimeMillis();
  }

  /** 同步 POST JSON，返回响应体；非 2xx 抛 {@link HttpStatusException}。抽出便于单测注入预置响应。 */
  protected String postJson(String url, String body) throws Exception {
    Request request = new Request.Builder().url(url).post(RequestBody.create(body, JSON)).build();
    try (Response response = httpClient.newCall(request).execute()) {
      int status = response.code();
      if (status / 100 != 2) {
        throw new HttpStatusException(status);
      }
      return response.body() == null ? "" : response.body().string();
    }
  }

  /** HTTP 非 2xx 的内部信号，承载状态码供上层折叠为 failure。 */
  protected static final class HttpStatusException extends Exception {
    private final int status;

    HttpStatusException(int status) {
      super("http status " + status);
      this.status = status;
    }

    int status() {
      return status;
    }
  }
}
