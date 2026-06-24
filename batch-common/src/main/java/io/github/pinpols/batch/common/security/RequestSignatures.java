package io.github.pinpols.batch.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 请求签名契约（方案 A：以 api_key 本身为 HMAC 密钥）。
 *
 * <p>这是服务端验签与 5 语言 SDK 签名的<b>唯一权威源</b>，canonical 串与算法必须逐字节一致：
 *
 * <pre>
 *   canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
 *   signature = hex(hmacSha256(apiKey, canonical))   // 小写 hex
 * </pre>
 *
 * <p>配套 header：{@code X-Batch-Timestamp}（epoch millis）、{@code X-Batch-Nonce}、{@code
 * X-Batch-Signature}。 防重放靠 timestamp 时钟偏移窗口 + nonce 一次性；防篡改靠 body 摘要纳入签名。
 *
 * <p><b>边界</b>：方案 A 不防 api_key 被盗后冒充（盗 key 也能签）；那由 TLS + key 轮换 + 限流覆盖。本契约的职责是防重放与防篡改。
 */
public final class RequestSignatures {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final String HMAC_ALG = "HmacSHA256";

  private RequestSignatures() {}

  /** body 的 SHA-256 小写 hex；null/空 body 走空字节数组（结果为公认空串常量）。 */
  public static String bodySha256Hex(byte[] body) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return toHex(md.digest(body == null ? new byte[0] : body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** 拼 canonical 串；method 统一大写，body 取 SHA-256 hex。 */
  public static String canonicalString(
      String method, String path, String timestamp, String nonce, byte[] body) {
    return upper(method)
        + '\n'
        + nullToEmpty(path)
        + '\n'
        + nullToEmpty(timestamp)
        + '\n'
        + nullToEmpty(nonce)
        + '\n'
        + bodySha256Hex(body);
  }

  /** 以 apiKey 为 HMAC-SHA256 密钥对 canonical 串签名，返回小写 hex。 */
  public static String sign(
      String apiKey, String method, String path, String timestamp, String nonce, byte[] body) {
    String canonical = canonicalString(method, path, timestamp, nonce, body);
    return hmacSha256Hex(apiKey, canonical);
  }

  private static String hmacSha256Hex(String key, String data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALG);
      mac.init(new SecretKeySpec(nullToEmpty(key).getBytes(StandardCharsets.UTF_8), HMAC_ALG));
      return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
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

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
