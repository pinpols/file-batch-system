package io.github.pinpols.batch.sdk.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SDK 侧请求签名（方案 A，以 api_key 为 HMAC 密钥）。
 *
 * <p>必须与服务端 {@code io.github.pinpols.batch.common.security.RequestSignatures} 逐字节一致 —— 二者由 SDK
 * 测试里的契约一致性用例钉死。 SDK core 不依赖 batch-common（避免拖入 MyBatis/Flyway/Redis），故此处独立实现同一算法：
 *
 * <pre>
 *   canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
 *   signature = hex(hmacSha256(apiKey, canonical))
 * </pre>
 */
public final class RequestSigner {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private RequestSigner() {}

  public static String bodySha256Hex(byte[] body) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return toHex(md.digest(body == null ? new byte[0] : body));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String canonicalString(
      String method, String path, String timestamp, String nonce, byte[] body) {
    return upper(method)
        + '\n'
        + nz(path)
        + '\n'
        + nz(timestamp)
        + '\n'
        + nz(nonce)
        + '\n'
        + bodySha256Hex(body);
  }

  public static String sign(
      String apiKey, String method, String path, String timestamp, String nonce, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(nz(apiKey).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(
          mac.doFinal(
              canonicalString(method, path, timestamp, nonce, body)
                  .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 failed", e);
    }
  }

  private static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }

  private static String upper(String s) {
    return s == null ? "" : s.toUpperCase(Locale.ROOT);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
