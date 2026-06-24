package io.github.pinpols.batch.orchestrator.security;

import io.github.pinpols.batch.common.security.RequestSignatures;
import io.github.pinpols.batch.common.security.SecretComparator;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 服务端请求签名校验（方案 A：以 api_key 为 HMAC 密钥）。
 *
 * <p>校验顺序：缺头 → 时钟偏移 → 签名 → nonce 一次性。<b>签名先于 nonce</b>，避免未签/错签请求白白消耗（污染）nonce 空间。
 */
@Component
@RequiredArgsConstructor
public class RequestSignatureVerifier {

  public enum Result {
    OK,
    MISSING_HEADERS,
    CLOCK_SKEW,
    BAD_SIGNATURE,
    REPLAY
  }

  /** 待校验的签名请求；body 为原始字节，tenantId 为鉴权解析出的租户（nonce 归属域）。 */
  public record SignedRequest(
      String apiKey,
      String method,
      String path,
      byte[] body,
      String timestamp,
      String nonce,
      String signature,
      String tenantId) {}

  private final RequestSigningProperties properties;
  private final NonceStore nonceStore;

  public Result verify(SignedRequest req, long nowMillis) {
    if (!Texts.hasText(req.timestamp())
        || !Texts.hasText(req.nonce())
        || !Texts.hasText(req.signature())) {
      return Result.MISSING_HEADERS;
    }
    long ts;
    try {
      ts = Long.parseLong(req.timestamp().trim());
    } catch (NumberFormatException e) {
      return Result.CLOCK_SKEW;
    }
    long skewMillis = properties.getClockSkewSeconds() * 1000L;
    if (Math.abs(nowMillis - ts) > skewMillis) {
      return Result.CLOCK_SKEW;
    }
    String expected =
        RequestSignatures.sign(
            req.apiKey(), req.method(), req.path(), req.timestamp(), req.nonce(), req.body());
    if (!SecretComparator.constantTimeEquals(expected, req.signature())) {
      return Result.BAD_SIGNATURE;
    }
    if (!nonceStore.registerIfAbsent(
        req.tenantId(), req.nonce(), Duration.ofMillis(skewMillis * 2))) {
      return Result.REPLAY;
    }
    return Result.OK;
  }
}
