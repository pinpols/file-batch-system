package io.github.pinpols.batch.orchestrator.security;

import io.github.pinpols.batch.common.security.RequestSignatures;
import io.github.pinpols.batch.common.security.SecretComparator;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Duration;
import java.util.regex.Pattern;
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

  private static final int TIMESTAMP_MIN_LENGTH = 10;
  private static final int TIMESTAMP_MAX_LENGTH = 17;
  private static final int NONCE_MIN_LENGTH = 8;
  private static final int NONCE_MAX_LENGTH = 128;
  private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\d{10,17}");
  private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern SIGNATURE_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public enum Result {
    OK,
    MISSING_HEADERS,
    CLOCK_SKEW,
    BAD_SIGNATURE,
    REPLAY
  }

  /** 待校验的签名请求；body 为原始字节，tenantId 为鉴权解析出的租户（nonce 归属域）。 */
  @SuppressWarnings("java:S6218")
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
    if (!hasValidTimestampShape(req.timestamp())) {
      return Result.CLOCK_SKEW;
    }
    if (!hasValidNonceShape(req.nonce()) || !hasValidSignatureShape(req.signature())) {
      return Result.BAD_SIGNATURE;
    }
    long ts;
    try {
      ts = Long.parseLong(req.timestamp());
    } catch (NumberFormatException e) {
      return Result.CLOCK_SKEW;
    }
    long skewMillis = properties.getClockSkewSeconds() * 1000L;
    if (Math.abs(nowMillis - ts) > skewMillis) {
      return Result.CLOCK_SKEW;
    }
    String expected = RequestSignatures.sign(
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

  private static boolean hasValidTimestampShape(String value) {
    int length = value.length();
    return length >= TIMESTAMP_MIN_LENGTH
        && length <= TIMESTAMP_MAX_LENGTH
        && TIMESTAMP_PATTERN.matcher(value).matches();
  }

  private static boolean hasValidNonceShape(String value) {
    int length = value.length();
    return length >= NONCE_MIN_LENGTH
        && length <= NONCE_MAX_LENGTH
        && NONCE_PATTERN.matcher(value).matches();
  }

  private static boolean hasValidSignatureShape(String value) {
    return SIGNATURE_PATTERN.matcher(value).matches();
  }
}
