package io.github.pinpols.batch.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 共享密钥/secret 的常量时间比对。
 *
 * <p>直接对原文调 {@link MessageDigest#isEqual} 时,两端长度不同会在 O(1) 内短路返回,通过响应时间泄漏期望 secret 的长度,
 * 缩小暴力破解空间。本工具先把两端各自 SHA-256 成定长 32 字节再比对:长度恒定,内容比对走常量时间,消除长度侧信道。
 */
public final class SecretComparator {

  private SecretComparator() {}

  /**
   * 常量时间比较两个 secret 是否相等,且不泄漏长度。
   *
   * @param expected 服务端配置的期望值(可空 → 一律不匹配)
   * @param provided 客户端提供的值(可空 → 一律不匹配)
   */
  public static boolean constantTimeEquals(String expected, String provided) {
    if (expected == null || provided == null) {
      return false;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] expectedHash = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
      MessageDigest digest2 = MessageDigest.getInstance("SHA-256");
      byte[] providedHash = digest2.digest(provided.getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(expectedHash, providedHash);
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 是 JDK 必备算法,不可能缺失;真缺失则保守判否。
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
