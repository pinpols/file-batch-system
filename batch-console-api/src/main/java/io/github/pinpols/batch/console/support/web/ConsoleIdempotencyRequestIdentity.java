package io.github.pinpols.batch.console.support.web;

import io.github.pinpols.batch.common.utils.Texts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 保持写请求的 Redis 身份和持久完成记录身份使用同一计算方式。 */
public final class ConsoleIdempotencyRequestIdentity {

  private static final String KEY_PREFIX = "console:idempotency:";

  private ConsoleIdempotencyRequestIdentity() {}

  public static String tenantId(String tenantHeader) {
    return Texts.hasText(tenantHeader) ? tenantHeader.trim() : "_";
  }

  public static String redisKey(
      String tenantHeader, String method, String requestUri, String idempotencyKey) {
    return KEY_PREFIX
        + tenantId(tenantHeader)
        + ":"
        + method.toUpperCase(Locale.ROOT)
        + ":"
        + requestUri
        + ":"
        + idempotencyKey.trim();
  }

  /** 使用原写请求的方法和路径计算，不能替换为结果查询请求的方法和路径。 */
  public static String durableKey(
      String tenantHeader, String method, String requestUri, String idempotencyKey) {
    return "console-http:" + requestHash(tenantHeader, method, requestUri, idempotencyKey);
  }

  /** 摘要用于关联原请求，不持久化请求中的幂等键。 */
  public static String requestHash(
      String tenantHeader, String method, String requestUri, String idempotencyKey) {
    String redisKey = redisKey(tenantHeader, method, requestUri, idempotencyKey);
    try {
      MessageDigest digestGenerator = MessageDigest.getInstance("SHA-256");
      byte[] redisKeyBytes = redisKey.getBytes(StandardCharsets.UTF_8);
      byte[] digest = digestGenerator.digest(redisKeyBytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "SHA-256 is required for durable idempotency keys", exception);
    }
  }
}
