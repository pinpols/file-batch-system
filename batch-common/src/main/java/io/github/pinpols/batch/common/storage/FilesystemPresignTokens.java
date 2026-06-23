package io.github.pinpols.batch.common.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * FS 后端 presign 令牌的签发 / 校验工具（Phase 2 §4①）。
 *
 * <p>设计参考 Rails ActiveStorage {@code DiskService}：本地 FS 没有"存储直发签名 URL"机制，所以签一个 HMAC 能力令牌 ({@code
 * bucket + key + expEpochSec}，应用密钥 HMAC-SHA256 签名)，由应用下载端点校验后从磁盘流式回放。
 *
 * <p>令牌不含内容哈希、不绑定 IP，纯能力令牌——所以必须有较短 TTL（建议 ≤ 几分钟）。 校验侧用常时间比较（{@link MessageDigest#isEqual}）防 timing
 * 攻击。
 */
public final class FilesystemPresignTokens {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private FilesystemPresignTokens() {}

  /**
   * 签发令牌，返回 Base64-URL 编码的签名（不含 padding）。
   *
   * @param bucket bucket 名
   * @param key 对象 key
   * @param expiresAt 过期时刻（Epoch 秒以下精度被舍去）
   * @param secret HMAC 密钥
   * @return Base64-URL 签名串
   */
  public static String sign(String bucket, String key, Instant expiresAt, String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("presign secret must not be blank");
    }
    long expEpochSec = expiresAt.getEpochSecond();
    return signRaw(bucket, key, expEpochSec, secret);
  }

  /**
   * 校验令牌：常时间比较签名 + 检查未过期。任一不通过返回 {@code false}。
   *
   * @param bucket bucket 名
   * @param key 对象 key
   * @param expEpochSec 令牌内携带的过期 Epoch 秒
   * @param signature 令牌内携带的签名（Base64-URL）
   * @param secret HMAC 密钥
   * @return true 表示签名匹配且未过期
   */
  public static boolean verify(
      String bucket, String key, long expEpochSec, String signature, String secret) {
    if (signature == null || secret == null || secret.isBlank()) {
      return false;
    }
    if (Instant.now().getEpochSecond() > expEpochSec) {
      return false;
    }
    String expected = signRaw(bucket, key, expEpochSec, secret);
    byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
    byte[] actualBytes = signature.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expectedBytes, actualBytes);
  }

  /** 拼装下载 URL：{@code <baseUrl>?b=<bucket>&k=<urlEncoded key>&e=<exp>&s=<sig>}。 */
  public static String buildUrl(
      String baseUrl, String bucket, String key, long expEpochSec, String signature) {
    String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
    String encodedBucket = URLEncoder.encode(bucket, StandardCharsets.UTF_8);
    return baseUrl
        + (baseUrl.contains("?") ? "&" : "?")
        + "b="
        + encodedBucket
        + "&k="
        + encodedKey
        + "&e="
        + expEpochSec
        + "&s="
        + signature;
  }

  private static String signRaw(String bucket, String key, long expEpochSec, String secret) {
    String payload = bucket + "|" + key + "|" + expEpochSec;
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute presign HMAC", ex);
    }
  }
}
