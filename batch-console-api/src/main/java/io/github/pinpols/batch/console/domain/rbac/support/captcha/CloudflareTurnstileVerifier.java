package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Turnstile 验证码校验。{@code provider=cloudflare} 装配。
 *
 * <p>token = 前端 Turnstile 控件回调拿到的 {@code cf-turnstile-response};服务端 POST 到 {@code siteverify} 端点带上
 * {@code secret + response + remoteip} 二次校验,以服务端响应的 {@code success} 布尔为准。token
 * 为空直接失败、不走网络;网络/解析异常一律保守判失败。
 *
 * <p>定位:配合 IP 限流 + 失败退避做纵深的"抬门槛"。日志只打净化后的元信息,绝不打 token / secret。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.captcha.provider", havingValue = "cloudflare")
public class CloudflareTurnstileVerifier implements CaptchaVerifier {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final CaptchaProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public CloudflareTurnstileVerifier(CaptchaProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public CaptchaResult verify(String token, String clientIp) {
    if (token == null || token.isBlank()) {
      return CaptchaResult.fail("missing token");
    }
    StringBuilder body = new StringBuilder();
    body.append("secret=").append(encode(properties.getSecretKey()));
    body.append("&response=").append(encode(token));
    if (clientIp != null && !clientIp.isBlank()) {
      body.append("&remoteip=").append(encode(clientIp));
    }
    try {
      String json = postForm(properties.getCloudflareVerifyEndpoint(), body.toString());
      JsonNode root = objectMapper.readTree(json);
      if (root.path("success").asBoolean(false)) {
        return CaptchaResult.ok();
      }
      JsonNode errorCodes = root.path("error-codes");
      return CaptchaResult.fail("turnstile rejected: " + errorCodes.toString());
    } catch (Exception ex) {
      // 净化:只打异常类型/消息,绝不打 token(用户可控)或 secret。
      log.warn(
          "captcha turnstile verify error: {} ip={}",
          ex.toString(),
          CaptchaCrypto.sanitizeForLog(clientIp),
          ex);
      return CaptchaResult.fail("turnstile verify error");
    }
  }

  /** 执行 application/x-www-form-urlencoded POST,返回响应体字符串。抽成 protected 以便单测覆盖、无网络验证 verify 各分支。 */
  protected String postForm(String url, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return response.body();
  }

  @Override
  public String provider() {
    return "cloudflare";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
