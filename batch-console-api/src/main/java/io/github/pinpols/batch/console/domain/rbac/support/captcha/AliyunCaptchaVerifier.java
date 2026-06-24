package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云验证码 2.0 校验。{@code provider=aliyun} 装配。
 *
 * <p>token = 前端阿里云验证码控件回调拿到的 {@code captchaVerifyParam}(一个不透明字符串,原样回传);服务端调阿里云 OpenAPI {@code
 * VerifyIntelligentCaptcha}(Version {@code 2023-03-05})带上 {@code SceneId} 二次校验,以响应 {@code
 * Body.Result.VerifyResult} 布尔为准。token 为空直接失败、不走网络;网络/解析异常一律保守判失败。
 *
 * <p>鉴权走阿里云 OpenAPI V3 签名(ACS3-HMAC-SHA256):构造 canonicalRequest → stringToSign →
 * HMAC-SHA256(AccessKeySecret) → Authorization 头。日志只打净化后的元信息,绝不打 token / AK / 签名。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.captcha.provider", havingValue = "aliyun")
public class AliyunCaptchaVerifier implements CaptchaVerifier {

  private static final String ALGORITHM = "ACS3-HMAC-SHA256";
  private static final String ACTION = "VerifyIntelligentCaptcha";
  private static final String VERSION = "2023-03-05";

  /** ISO8601 UTC,形如 2023-03-05T01:02:03Z,阿里云 x-acs-date 要求的格式。 */
  private static final DateTimeFormatter ISO8601 =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private final CaptchaProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AliyunCaptchaVerifier(CaptchaProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public CaptchaResult verify(String token, String clientIp) {
    if (token == null || token.isBlank()) {
      return CaptchaResult.fail("missing token");
    }
    try {
      String host = properties.getAliyunEndpoint();
      String url = "https://" + host + "/";
      String body =
          objectMapper.writeValueAsString(
              Map.of("CaptchaVerifyParam", token, "SceneId", properties.getAliyunSceneId()));

      Map<String, String> headers = signedHeaders(host, body);
      String json = postJson(url, headers, body);

      JsonNode root = objectMapper.readTree(json);
      JsonNode result = root.path("Body").path("Result");
      if (result.path("VerifyResult").asBoolean(false)) {
        return CaptchaResult.ok();
      }
      return CaptchaResult.fail("aliyun rejected: " + root.path("Body").path("Code").asText(""));
    } catch (Exception ex) {
      // 净化:只打异常类型/消息,绝不打 token(用户可控)/ AK / 签名。
      log.warn(
          "captcha aliyun verify error: {} ip={}",
          ex.toString(),
          CaptchaCrypto.sanitizeForLog(clientIp),
          ex);
      return CaptchaResult.fail("aliyun verify error");
    }
  }

  /** 构造 ACS3-HMAC-SHA256 签名所需的全部请求头(含 Authorization)。date / nonce 走 protected 方法以便测试固定值保证确定性。 */
  private Map<String, String> signedHeaders(String host, String body) throws Exception {
    String hashedPayload = CaptchaCrypto.sha256Hex(body);
    String date = acsDate();
    String nonce = nonce();

    // CanonicalHeaders 用到的头集合(小写、字典序),签名与实际发送须一致。
    TreeMap<String, String> canonicalHeaders = new TreeMap<>();
    canonicalHeaders.put("host", host);
    canonicalHeaders.put("x-acs-action", ACTION);
    canonicalHeaders.put("x-acs-content-sha256", hashedPayload);
    canonicalHeaders.put("x-acs-date", date);
    canonicalHeaders.put("x-acs-signature-nonce", nonce);
    canonicalHeaders.put("x-acs-version", VERSION);

    String authorization =
        buildAuthorization(
            "POST",
            "/",
            "",
            canonicalHeaders,
            hashedPayload,
            properties.getAliyunAccessKeyId(),
            properties.getAliyunAccessKeySecret());

    TreeMap<String, String> headers = new TreeMap<>(canonicalHeaders);
    headers.put("Authorization", authorization);
    return headers;
  }

  /** 执行 application/json POST,带上签名头,返回响应体字符串。抽成 protected 以便单测覆盖、无网络验证 verify 各分支。 */
  protected String postJson(String url, Map<String, String> headers, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    // host 头由 HttpClient 自行管理,不可手动 set,故签名头里跳过 host。
    headers.forEach(
        (k, v) -> {
          if (!"host".equalsIgnoreCase(k)) {
            builder.header(k, v);
          }
        });
    HttpResponse<String> response =
        httpClient.send(
            builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return response.body();
  }

  /** x-acs-date(ISO8601 UTC)。protected 以便测试覆盖为固定值保证签名确定性。 */
  protected String acsDate() {
    return ISO8601.format(Instant.now());
  }

  /** x-acs-signature-nonce(每请求唯一)。protected 以便测试覆盖为固定值保证签名确定性。 */
  protected String nonce() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  @Override
  public String provider() {
    return "aliyun";
  }

  // ── ACS3-HMAC-SHA256 签名(阿里云 OpenAPI V3) ──────────────────────────────
  // 算法见 https://help.aliyun.com/zh/sdk/product-overview/v3-request-structure-and-signature
  // 端到端签名(被阿里网关接受)需联调真 API 验;本仓无官方 golden 向量,单测仅断言结构/确定性。

  /**
   * 构造 Authorization 头(包级可见、静态,纯函数,便于单测断言 canonicalRequest / stringToSign 结构 + 确定性)。
   *
   * <p>canonicalRequest = method\nURI\nquery\ncanonicalHeaders\n\nsignedHeaders\nhashedPayload;
   * stringToSign = "ACS3-HMAC-SHA256\n" + hex(sha256(canonicalRequest));signature =
   * hex(HMAC_SHA256(secret, stringToSign))。
   */
  static String buildAuthorization(
      String httpMethod,
      String canonicalUri,
      String canonicalQuery,
      TreeMap<String, String> canonicalHeaders,
      String hashedPayload,
      String accessKeyId,
      String accessKeySecret)
      throws Exception {
    String signedHeaders = String.join(";", canonicalHeaders.navigableKeySet());

    StringBuilder headerLines = new StringBuilder();
    for (Map.Entry<String, String> e : canonicalHeaders.entrySet()) {
      headerLines.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
    }

    String canonicalRequest =
        httpMethod
            + '\n'
            + canonicalUri
            + '\n'
            + canonicalQuery
            + '\n'
            + headerLines
            + '\n'
            + signedHeaders
            + '\n'
            + hashedPayload;

    String stringToSign = ALGORITHM + '\n' + CaptchaCrypto.sha256Hex(canonicalRequest);
    String signature = CaptchaCrypto.hmacSha256Hex(accessKeySecret, stringToSign);

    return ALGORITHM
        + " Credential="
        + accessKeyId
        + ",SignedHeaders="
        + signedHeaders
        + ",Signature="
        + signature;
  }
}
