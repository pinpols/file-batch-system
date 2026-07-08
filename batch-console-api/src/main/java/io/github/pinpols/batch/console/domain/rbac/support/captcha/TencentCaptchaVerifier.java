package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 腾讯天御无感验证码校验。{@code provider=tencent} 装配。
 *
 * <p>token = 前端腾讯验证码控件回调拿到的 {@code ticket:randstr}(冒号拼接)。服务端用腾讯云 API 凭证以 <b>TC3-HMAC-SHA256</b> 签名调
 * {@code captcha} 产品的 {@code DescribeCaptchaResult} 验票,以响应的 {@code Response.CaptchaCode==1}
 * 为准。token 空 / 无冒号直接失败、不走网络; 网络 / 解析 / 签名异常一律保守判失败。
 *
 * <p>定位:配合 IP 限流 + 失败退避做纵深的"抬门槛"。日志只打净化后的元信息,绝不打 token / ticket / secret。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.captcha.provider", havingValue = "tencent")
public class TencentCaptchaVerifier implements CaptchaVerifier {

  /** Captcha 产品固定 API 版本。 */
  private static final String API_VERSION = "2019-07-22";

  /** 验票 Action。 */
  private static final String API_ACTION = "DescribeCaptchaResult";

  /** TC3 签名 service 名(对齐域名前缀)。 */
  private static final String SERVICE = "captcha";

  /** 无感 / 滑块统一为 9(平台只接这一种)。 */
  private static final int CAPTCHA_TYPE = 9;

  private static final String ALGORITHM = "TC3-HMAC-SHA256";
  private static final String CONTENT_TYPE = "application/json; charset=utf-8";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final CaptchaProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public TencentCaptchaVerifier(CaptchaProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public CaptchaResult verify(String token, String clientIp) {
    if (token == null || token.isBlank()) {
      return CaptchaResult.fail("missing token");
    }
    int sep = token.indexOf(':');
    if (sep <= 0 || sep == token.length() - 1) {
      return CaptchaResult.fail("malformed token: expect ticket:randstr");
    }
    String ticket = token.substring(0, sep);
    String randstr = token.substring(sep + 1);

    try {
      String payload = buildPayload(ticket, randstr, clientIp);
      String endpoint = properties.getTencentEndpoint();
      String host = URI.create(endpoint).getHost();
      long timestamp = epochSeconds();

      Map<String, String> headers =
          buildSignedHeaders(
              host,
              payload,
              timestamp,
              properties.getTencentSecretId(),
              properties.getTencentSecretKey());

      String json = postJson(endpoint, headers, payload);
      JsonNode response = objectMapper.readTree(json).path("Response");
      int captchaCode = response.path("CaptchaCode").asInt(-1);
      if (captchaCode == 1) {
        return CaptchaResult.ok();
      }
      String captchaMsg = response.path("CaptchaMsg").asText("");
      return CaptchaResult.fail("tencent rejected: code=" + captchaCode + " msg=" + captchaMsg);
    } catch (Exception ex) {
      // 净化:只打异常类型/消息,绝不打 token / ticket(用户可控)或 secret。
      log.warn(
          "captcha tencent verify error: {} ip={}",
          ex.toString(),
          CaptchaCrypto.sanitizeForLog(clientIp),
          ex);
      return CaptchaResult.fail("tencent verify error");
    }
  }

  /** 验票请求体 JSON。CaptchaAppId / AppSecretKey 取自配置(后端凭证,绝不下发 FE)。 */
  private String buildPayload(String ticket, String randstr, String clientIp)
      throws JsonProcessingException {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("CaptchaType", CAPTCHA_TYPE);
    body.put("Ticket", ticket);
    body.put("UserIp", clientIp == null ? "" : clientIp);
    body.put("Randstr", randstr);
    body.put("CaptchaAppId", properties.getTencentAppId());
    body.put("AppSecretKey", properties.getTencentAppSecretKey());
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 构造 TC3-HMAC-SHA256 鉴权所需的全部请求头(Authorization + X-TC-* + Content-Type + Host)。
   *
   * <p>抽出且参数化 host / payload / timestamp / 凭证,便于单测对照官方算法形状断言。
   */
  Map<String, String> buildSignedHeaders(
      String host, String payload, long timestamp, String secretId, String secretKey) {
    String date =
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(timestamp));
    String authorization = buildAuthorization(host, payload, timestamp, date, secretId, secretKey);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", authorization);
    headers.put("Content-Type", CONTENT_TYPE);
    headers.put("Host", host);
    headers.put("X-TC-Action", API_ACTION);
    headers.put("X-TC-Timestamp", Long.toString(timestamp));
    headers.put("X-TC-Version", API_VERSION);
    return headers;
  }

  /**
   * 按腾讯云 TC3-HMAC-SHA256 官方算法构造 Authorization 头。包级可见 + 静态,无外部依赖,便于单测对结构 / 确定性断言。
   *
   * @see <a href="https://cloud.tencent.com/document/api/213/30654">TC3 签名文档</a>
   */
  static String buildAuthorization(
      String host, String payload, long timestamp, String date, String secretId, String secretKey) {
    String canonicalRequest = buildCanonicalRequest(host, payload);
    String stringToSign = buildStringToSign(timestamp, date, canonicalRequest);
    String signature = sign(date, stringToSign, secretKey);
    String credentialScope = date + "/" + SERVICE + "/tc3_request";
    return ALGORITHM
        + " Credential="
        + secretId
        + "/"
        + credentialScope
        + ", SignedHeaders=content-type;host, Signature="
        + signature;
  }

  /**
   * 规范请求串:HTTPMethod\nCanonicalURI\nCanonicalQueryString\nCanonicalHeaders\nSignedHeaders\nHashedPayload。
   * POST / 无 query;CanonicalHeaders 为 content-type + host 小写排序、各自以 \n 结尾。
   */
  static String buildCanonicalRequest(String host, String payload) {
    String canonicalHeaders =
        "content-type:" + CONTENT_TYPE + "\n" + "host:" + host.toLowerCase(Locale.ROOT) + "\n";
    String hashedPayload = CaptchaCrypto.sha256Hex(payload);
    return "POST\n" + "/\n" + "\n" + canonicalHeaders + "content-type;host\n" + hashedPayload;
  }

  /** 待签名串:Algorithm\nTimestamp\nCredentialScope\nHashedCanonicalRequest。 */
  static String buildStringToSign(long timestamp, String date, String canonicalRequest) {
    String credentialScope = date + "/" + SERVICE + "/tc3_request";
    return ALGORITHM
        + "\n"
        + timestamp
        + "\n"
        + credentialScope
        + "\n"
        + CaptchaCrypto.sha256Hex(canonicalRequest);
  }

  /** 四段派生密钥后对 stringToSign 做 HMAC,输出小写 hex。 */
  static String sign(String date, String stringToSign, String secretKey) {
    byte[] secretDate =
        CaptchaCrypto.hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
    byte[] secretService = CaptchaCrypto.hmacSha256(secretDate, SERVICE);
    byte[] secretSigning = CaptchaCrypto.hmacSha256(secretService, "tc3_request");
    return CaptchaCrypto.hex(CaptchaCrypto.hmacSha256(secretSigning, stringToSign));
  }

  /** 时间戳来源,UTC 秒;protected 以便单测覆盖固定值做确定性断言。 */
  protected long epochSeconds() {
    return Instant.now().getEpochSecond();
  }

  /** 执行 application/json POST 带签名头,返回响应体字符串。抽 protected 以便单测覆盖返回预置 JSON、无网络验证各分支。 */
  protected String postJson(String url, Map<String, String> headers, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    headers.forEach(builder::header);
    HttpResponse<String> response =
        httpClient.send(
            builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return response.body();
  }

  @Override
  public String provider() {
    return "tencent";
  }
}
