package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 验证码 provider 共用的低层工具：SHA-256 / HMAC-SHA256 / hex / 日志净化。
 *
 * <p>抽出避免 Tencent(TC3)/Aliyun(ACS3)/Cloudflare 各自重复实现同一套密码学原语与日志净化；provider 各自的 canonicalRequest /
 * stringToSign / Authorization 组装仍留在各 verifier(签名协议不同)。
 */
final class CaptchaCrypto {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final String HMAC_SHA256 = "HmacSHA256";

  private CaptchaCrypto() {}

  /** 字节数组转小写 hex。 */
  static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }

  /** hex(sha256(utf8(data)))。 */
  static String sha256Hex(String data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return hex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /** HMAC-SHA256，原始字节密钥 → 原始字节(用于多段派生，如 TC3)。 */
  static byte[] hmacSha256(byte[] key, String message) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(key, HMAC_SHA256));
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException("HmacSHA256 unavailable", ex);
    }
  }

  /** hex(HMAC-SHA256(utf8(key), utf8(data)))。 */
  static String hmacSha256Hex(String key, String data) {
    return hex(hmacSha256(key.getBytes(StandardCharsets.UTF_8), data));
  }

  /** 用户可控值进日志前去除 CR/LF，防日志注入(伪造日志行)。null 归一为空串。 */
  static String sanitizeForLog(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("[\\r\\n]", "_");
  }
}
