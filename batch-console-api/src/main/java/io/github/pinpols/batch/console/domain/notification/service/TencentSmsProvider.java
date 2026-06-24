package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 腾讯云短信发送 provider（{@code batch.console.sms.provider == "tencent"}）。
 *
 * <p>调用腾讯云短信 OpenAPI {@code SendSms}（Action {@code SendSms}，Version {@code 2021-01-11}，service
 * {@code sms}，Region 取 {@link SmsProperties#getTencentRegion()}），采用<b>POST + JSON body</b>，按腾讯云
 * <b>TC3-HMAC-SHA256</b> 规则签名（canonicalRequest → stringToSign → 派生
 * SecretDate/SecretService/SecretSigning → HMAC-SHA256 → Authorization 头）。
 *
 * <p>per-channel 参数从 {@link NotificationMessage#configJson()} 取：{@code sdkAppId}（{@code
 * SmsSdkAppId}，必填）、{@code signName}（{@code SignName}，必填）、{@code templateId}（{@code
 * TemplateId}，必填）；缺任一即 {@link WebhookDeliveryResult#failure} 且<b>不走网络</b>。模板参数 {@code
 * TemplateParamSet} 取事件类型一项，超长截断。
 *
 * <p>凭证来自 {@link SmsProperties}（{@code tencentSecretId}/{@code tencentSecretKey}），仅后端持有。无状态、线程安全（单例
 * bean，共享 {@link HttpClient}）；所有失败折叠为 failure 而非抛异常。日志净化：<b>绝不打印手机号明文 / SecretId / SecretKey /
 * 签名</b>，手机号只打数量。投递前 {@link DnsResolveGuard#resolveAndValidate} 防 SSRF/rebinding。
 *
 * <p><b>验签状态</b>：腾讯云 TC3 端到端无官方 golden 向量，单测仅验结构 / 确定性 + 分支；真实签名正确性<b>需对接真 API 联调验签</b>。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.sms.provider", havingValue = "tencent")
public class TencentSmsProvider implements SmsProvider {

  private static final String ACTION = "SendSms";
  private static final String VERSION = "2021-01-11";
  private static final String SERVICE = "sms";
  private static final String ALGORITHM = "TC3-HMAC-SHA256";
  private static final String HTTP_METHOD = "POST";
  private static final String CANONICAL_URI = "/";
  private static final String CONTENT_TYPE = "application/json; charset=utf-8";

  /** 腾讯短信模板参数值的最大字符数，超出截断。 */
  private static final int MAX_TEMPLATE_PARAM_CHARS = 200;

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final DateTimeFormatter UTC_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final SmsProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public TencentSmsProvider(SmsProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public boolean supports(String provider) {
    return "tencent".equalsIgnoreCase(provider);
  }

  @Override
  public WebhookDeliveryResult send(List<String> phoneNumbers, NotificationMessage message) {
    if (phoneNumbers == null || phoneNumbers.isEmpty()) {
      return WebhookDeliveryResult.failure(null, "missing sms phoneNumbers");
    }

    String sdkAppId;
    String signName;
    String templateId;
    try {
      JsonNode config = objectMapper.readTree(message.configJson());
      sdkAppId = textOrNull(config, "sdkAppId");
      signName = textOrNull(config, "signName");
      templateId = textOrNull(config, "templateId");
    } catch (Exception ex) {
      log.warn(
          "tencent sms config parse failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "invalid sms config");
    }

    if (sdkAppId == null || sdkAppId.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms sdkAppId");
    }
    if (signName == null || signName.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms signName");
    }
    if (templateId == null || templateId.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms templateId");
    }

    String endpoint = properties.getTencentEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms endpoint");
    }

    String payload = buildRequestBody(phoneNumbers, sdkAppId, signName, templateId, message);

    String authorization;
    long timestamp = epochSeconds();
    try {
      authorization = buildAuthorization(payload, timestamp, endpoint);
    } catch (Exception ex) {
      log.warn(
          "tencent sms sign failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "sms sign failed");
    }

    String url = "https://" + endpoint + CANONICAL_URI;
    Map<String, String> headers =
        Map.of(
            "Host", endpoint,
            "Content-Type", CONTENT_TYPE,
            "X-TC-Action", ACTION,
            "X-TC-Version", VERSION,
            "X-TC-Timestamp", Long.toString(timestamp),
            "X-TC-Region", properties.getTencentRegion(),
            "Authorization", authorization);

    try {
      // SSRF/rebinding 防护:投递前二次解析校验 endpoint host IP 不落内网/回环/链路本地。
      DnsResolveGuard.resolveAndValidate(endpoint);
      String response = postJson(url, headers, payload);
      JsonNode root = objectMapper.readTree(response);
      JsonNode resp = root.path("Response");

      // 平台级错误(签名/参数/限流等)走 Response.Error。
      JsonNode error = resp.path("Error");
      if (error.has("Code")) {
        String errCode = error.path("Code").asText("");
        log.warn(
            "tencent sms api error channel={} recipients={} code={}",
            message.channelCode(),
            phoneNumbers.size(),
            errCode);
        return WebhookDeliveryResult.failure(200, "sms error=" + errCode);
      }

      JsonNode statusSet = resp.path("SendStatusSet");
      if (!statusSet.isArray() || statusSet.isEmpty()) {
        log.warn(
            "tencent sms empty status channel={} recipients={}",
            message.channelCode(),
            phoneNumbers.size());
        return WebhookDeliveryResult.failure(200, "sms empty status");
      }
      for (JsonNode status : statusSet) {
        String code = status.path("Code").asText("");
        if (!"Ok".equals(code)) {
          // 不打 Message(可能含被风控的内容),只打首个非 Ok code。
          log.warn(
              "tencent sms rejected channel={} recipients={} code={}",
              message.channelCode(),
              phoneNumbers.size(),
              code);
          return WebhookDeliveryResult.failure(200, "sms code=" + code);
        }
      }
      return WebhookDeliveryResult.ok();
    } catch (HttpStatusException ex) {
      log.warn(
          "tencent sms http failed channel={} recipients={} status={}",
          message.channelCode(),
          phoneNumbers.size(),
          ex.status());
      return WebhookDeliveryResult.failure(ex.status(), "sms http status=" + ex.status());
    } catch (Exception ex) {
      log.warn(
          "tencent sms delivery failed channel={} recipients={} reason={}",
          message.channelCode(),
          phoneNumbers.size(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, ex.getClass().getSimpleName());
    }
  }

  /**
   * 构造 SendSms 请求体 JSON：{@code PhoneNumberSet}（E.164 手机号）、{@code SmsSdkAppId}、{@code
   * SignName}、{@code TemplateId}、{@code TemplateParamSet}（事件类型一项，超长截断）。
   */
  private String buildRequestBody(
      List<String> phoneNumbers,
      String sdkAppId,
      String signName,
      String templateId,
      NotificationMessage message) {
    ObjectNode root = objectMapper.createObjectNode();
    ArrayNode phones = root.putArray("PhoneNumberSet");
    for (String phone : phoneNumbers) {
      phones.add(phone);
    }
    root.put("SmsSdkAppId", sdkAppId);
    root.put("SignName", signName);
    root.put("TemplateId", templateId);
    ArrayNode params = root.putArray("TemplateParamSet");
    params.add(templateParam(message));
    return root.toString();
  }

  /** 模板参数值取事件类型，超长截断，保持简单。 */
  private String templateParam(NotificationMessage message) {
    String eventType =
        (message.payload() != null && message.payload().eventType() != null)
            ? message.payload().eventType()
            : "UNKNOWN";
    if (eventType.length() > MAX_TEMPLATE_PARAM_CHARS) {
      eventType = eventType.substring(0, MAX_TEMPLATE_PARAM_CHARS);
    }
    return eventType;
  }

  /**
   * 按 TC3-HMAC-SHA256 构造 Authorization 头：canonicalRequest → hashedCanonicalRequest → stringToSign →
   * SecretDate = HMAC("TC3"+key, date) → SecretService = HMAC(SecretDate, service) → SecretSigning
   * = HMAC(SecretService, "tc3_request") → signature = hex(HMAC(SecretSigning, stringToSign))。
   */
  private String buildAuthorization(String payload, long timestamp, String endpoint)
      throws Exception {
    String canonicalHeaders = "content-type:" + CONTENT_TYPE + '\n' + "host:" + endpoint + '\n';
    String signedHeaders = "content-type;host";
    String hashedPayload = sha256Hex(payload);

    String canonicalRequest =
        HTTP_METHOD
            + '\n'
            + CANONICAL_URI
            + '\n'
            // canonical query string 为空(参数全在 body)。
            + ""
            + '\n'
            + canonicalHeaders
            + '\n'
            + signedHeaders
            + '\n'
            + hashedPayload;

    String date = UTC_DATE_FORMAT.format(Instant.ofEpochSecond(timestamp));
    String credentialScope = date + "/" + SERVICE + "/tc3_request";
    String stringToSign =
        ALGORITHM + '\n' + timestamp + '\n' + credentialScope + '\n' + sha256Hex(canonicalRequest);

    byte[] secretDate =
        hmacSha256(
            ("TC3" + properties.getTencentSecretKey()).getBytes(StandardCharsets.UTF_8), date);
    byte[] secretService = hmacSha256(secretDate, SERVICE);
    byte[] secretSigning = hmacSha256(secretService, "tc3_request");
    String signature = toHex(hmacSha256(secretSigning, stringToSign));

    return ALGORITHM
        + " Credential="
        + properties.getTencentSecretId()
        + "/"
        + credentialScope
        + ", SignedHeaders="
        + signedHeaders
        + ", Signature="
        + signature;
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return toHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("sha256 unavailable", ex);
    }
  }

  private static byte[] hmacSha256(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static String textOrNull(JsonNode config, String field) {
    JsonNode node = config.get(field);
    return (node == null || node.isNull()) ? null : node.asText();
  }

  /** 当前 epoch 秒（UTC），抽出便于单测固定签名输入。 */
  protected long epochSeconds() {
    return Instant.now().getEpochSecond();
  }

  /** 同步 POST（JSON body，签名头随请求发出），返回响应体；非 2xx 抛 {@link HttpStatusException}。抽出便于单测注入预置响应。 */
  protected String postJson(String url, Map<String, String> headers, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
    for (Map.Entry<String, String> e : headers.entrySet()) {
      builder.header(e.getKey(), e.getValue());
    }
    HttpRequest request =
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    int status = response.statusCode();
    if (status / 100 != 2) {
      throw new HttpStatusException(status);
    }
    return response.body();
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
