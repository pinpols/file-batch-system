package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信 {@link SmsProvider} 实现（provider == "aliyun"）。
 *
 * <p>调用阿里云短信 OpenAPI {@code SendSms}（Action {@code SendSms}，Version {@code 2017-05-25}），采用 RPC 风格
 * <b>GET + query 参数</b>（body 空，{@code x-acs-content-sha256 = sha256("")}），按阿里云
 * <b>ACS3-HMAC-SHA256</b> 规则签名（canonicalRequest → stringToSign → HMAC-SHA256 → Authorization 头）。
 *
 * <p>手机号<b>由入参 {@code phoneNumbers}</b> 提供（{@link SmsNotificationSender} 已从 config_json 解析），逗号 join
 * 成阿里要的 {@code PhoneNumbers}；不再自行从 config_json 解析手机号。{@code signName}（短信签名，必填）、{@code
 * templateCode}（模板 code，必填） 仍从 {@link NotificationMessage#configJson()} 取；缺任一即 {@link
 * WebhookDeliveryResult#failure} 且<b>不走网络</b>。模板参数 {@code TemplateParam} 取事件类型构造 {@code {"event":
 * <eventType>}}，超长截断（阿里短信模板参数有长度限制）。
 *
 * <p>凭证来自专属 {@link SmsProperties}（与验证码 AK/SK 隔离）。无状态、线程安全（单例 bean，共享 {@link HttpClient}）；所有失败折叠为
 * failure 而非抛异常。日志净化：<b>绝不打印手机号明文 / AK / SK / 签名</b>，手机号只打数量。
 *
 * <p><b>验签状态</b>：阿里云 ACS3 端到端无官方 golden 向量，单测仅验结构 / 确定性 + 分支；真实签名正确性<b>需对接真 API 联调验签</b>。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.sms.provider", havingValue = "aliyun")
public class AliyunSmsProvider implements SmsProvider {

  private static final String ACTION = "SendSms";
  private static final String VERSION = "2017-05-25";
  private static final String ALGORITHM = "ACS3-HMAC-SHA256";
  private static final String HTTP_METHOD = "GET";
  private static final String CANONICAL_URI = "/";

  /** 阿里短信模板参数值的最大字符数，超出截断，避免触模板参数长度限制。 */
  private static final int MAX_TEMPLATE_PARAM_CHARS = 200;

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final DateTimeFormatter ACS_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final SmsProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AliyunSmsProvider(SmsProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public boolean supports(String provider) {
    return "aliyun".equalsIgnoreCase(provider);
  }

  @Override
  public WebhookDeliveryResult send(List<String> phoneNumbers, NotificationMessage message) {
    if (phoneNumbers == null || phoneNumbers.isEmpty()) {
      return WebhookDeliveryResult.failure(null, "missing sms phoneNumbers");
    }
    String joinedPhoneNumbers = String.join(",", phoneNumbers);
    if (joinedPhoneNumbers.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms phoneNumbers");
    }

    String signName;
    String templateCode;
    try {
      JsonNode config = objectMapper.readTree(message.configJson());
      signName = textOrNull(config, "signName");
      templateCode = textOrNull(config, "templateCode");
    } catch (Exception ex) {
      log.warn(
          "aliyun sms config parse failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "invalid sms config");
    }

    if (signName == null || signName.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms signName");
    }
    if (templateCode == null || templateCode.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms templateCode");
    }

    String endpoint = properties.getAliyunEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing sms endpoint");
    }

    SortedMap<String, String> queryParams = new TreeMap<>();
    queryParams.put("PhoneNumbers", joinedPhoneNumbers);
    queryParams.put("SignName", signName);
    queryParams.put("TemplateCode", templateCode);
    queryParams.put("TemplateParam", buildTemplateParam(message));

    String authorization;
    String canonicalQuery;
    String acsDate = acsDate();
    String nonce = nonce();
    try {
      canonicalQuery = canonicalQueryString(queryParams);
      authorization = buildAuthorization(queryParams, acsDate, nonce, endpoint);
    } catch (Exception ex) {
      log.warn(
          "aliyun sms sign failed channel={} reason={}",
          message.channelCode(),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, "sms sign failed");
    }

    String url = "https://" + endpoint + CANONICAL_URI + "?" + canonicalQuery;
    Map<String, String> headers =
        Map.of(
            "host", endpoint,
            "x-acs-action", ACTION,
            "x-acs-version", VERSION,
            "x-acs-date", acsDate,
            "x-acs-signature-nonce", nonce,
            "x-acs-content-sha256", sha256Hex(""),
            "Authorization", authorization);

    try {
      // SSRF/rebinding 防护:投递前二次解析校验 endpoint host IP 不落内网/回环/链路本地。
      DnsResolveGuard.resolveAndValidate(endpoint);
      String response = postJson(url, headers);
      JsonNode node = objectMapper.readTree(response);
      String code = node.path("Code").asText("");
      if ("OK".equals(code)) {
        return WebhookDeliveryResult.ok();
      }
      // 不打印 Message(可能含被风控的内容),只打脱敏 Code。
      log.warn(
          "aliyun sms rejected channel={} recipients={} code={}",
          message.channelCode(),
          recipientCount(phoneNumbers),
          code);
      return WebhookDeliveryResult.failure(200, "sms code=" + code);
    } catch (HttpStatusException ex) {
      log.warn(
          "aliyun sms http failed channel={} recipients={} status={}",
          message.channelCode(),
          recipientCount(phoneNumbers),
          ex.status());
      return WebhookDeliveryResult.failure(ex.status(), "sms http status=" + ex.status());
    } catch (Exception ex) {
      log.warn(
          "aliyun sms delivery failed channel={} recipients={} reason={}",
          message.channelCode(),
          recipientCount(phoneNumbers),
          ex.getClass().getSimpleName());
      return WebhookDeliveryResult.failure(null, ex.getClass().getSimpleName());
    }
  }

  /** 构造模板参数 {@code {"event": <eventType>}}，值超长截断，保持简单。 */
  private String buildTemplateParam(NotificationMessage message) {
    String eventType =
        (message.payload() != null && message.payload().eventType() != null)
            ? message.payload().eventType()
            : "UNKNOWN";
    if (eventType.length() > MAX_TEMPLATE_PARAM_CHARS) {
      eventType = eventType.substring(0, MAX_TEMPLATE_PARAM_CHARS);
    }
    ObjectNode root = objectMapper.createObjectNode();
    root.put("event", eventType);
    return root.toString();
  }

  /**
   * 按 ACS3-HMAC-SHA256 构造 Authorization 头：canonicalRequest → hashedCanonicalRequest → stringToSign
   * → HMAC-SHA256(secret) → 十六进制 signature。
   */
  private String buildAuthorization(
      SortedMap<String, String> queryParams, String acsDate, String nonce, String endpoint)
      throws Exception {
    String hashedPayload = sha256Hex("");

    // signedHeaders 必须按字典序且与 canonicalHeaders 一致。
    SortedMap<String, String> canonicalHeaders = new TreeMap<>();
    canonicalHeaders.put("host", endpoint);
    canonicalHeaders.put("x-acs-action", ACTION);
    canonicalHeaders.put("x-acs-content-sha256", hashedPayload);
    canonicalHeaders.put("x-acs-date", acsDate);
    canonicalHeaders.put("x-acs-signature-nonce", nonce);
    canonicalHeaders.put("x-acs-version", VERSION);

    StringBuilder canonicalHeaderStr = new StringBuilder();
    StringBuilder signedHeadersStr = new StringBuilder();
    for (Map.Entry<String, String> e : canonicalHeaders.entrySet()) {
      canonicalHeaderStr.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
      if (signedHeadersStr.length() > 0) {
        signedHeadersStr.append(';');
      }
      signedHeadersStr.append(e.getKey());
    }
    String signedHeaders = signedHeadersStr.toString();

    String canonicalRequest =
        HTTP_METHOD
            + '\n'
            + CANONICAL_URI
            + '\n'
            + canonicalQueryString(queryParams)
            + '\n'
            + canonicalHeaderStr
            + '\n'
            + signedHeaders
            + '\n'
            + hashedPayload;

    String stringToSign = ALGORITHM + '\n' + sha256Hex(canonicalRequest);
    String signature = hmacSha256Hex(properties.getAliyunAccessKeySecret(), stringToSign);

    return ALGORITHM
        + " Credential="
        + properties.getAliyunAccessKeyId()
        + ",SignedHeaders="
        + signedHeaders
        + ",Signature="
        + signature;
  }

  /** ACS3 canonical query：key 字典序，key/value 均 RFC3986 百分号编码，{@code key=value} 以 {@code &} 连接。 */
  private static String canonicalQueryString(SortedMap<String, String> params) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
    }
    return sb.toString();
  }

  /** RFC3986 编码：在 URLEncoder 基础上把 {@code + * %7E} 修正为阿里云期望的形式。 */
  private static String percentEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~");
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return toHex(hash);
    } catch (Exception ex) {
      throw new IllegalStateException("sha256 unavailable", ex);
    }
  }

  private static String hmacSha256Hex(String secret, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    return toHex(digest);
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

  /** 手机号脱敏：只暴露收件人数量,绝不打明文。 */
  private static int recipientCount(List<String> phoneNumbers) {
    return phoneNumbers == null ? 0 : phoneNumbers.size();
  }

  /** 当前 ACS3 日期（UTC，ISO8601），抽出便于单测固定签名输入。 */
  protected String acsDate() {
    return ACS_DATE_FORMAT.format(Instant.now());
  }

  /** 签名随机数（去重防重放），抽出便于单测固定。 */
  protected String nonce() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 同步 GET（RPC 风格，body 空，签名头随请求发出），返回响应体；非 2xx 抛 {@link HttpStatusException}。抽出便于单测注入预置响应。 */
  protected String postJson(String url, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
    for (Map.Entry<String, String> e : headers.entrySet()) {
      builder.header(e.getKey(), e.getValue());
    }
    HttpRequest request = builder.GET().build();
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
