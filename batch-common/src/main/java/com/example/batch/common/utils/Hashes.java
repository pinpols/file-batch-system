package com.example.batch.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 通用哈希工具:抹掉重复的 SHA-256 + hex 编码样板。
 *
 * <p>典型用途:
 *
 * <ul>
 *   <li>审计 / 日志里把 IP / UA 等弱敏感字段做短哈希(8 字节 / 16 hex)写入,既能 drift 比对又不直接落明文
 *   <li>JWT IP/UA binding hash(参考 {@code ConsoleJwtService#sha256Short})
 * </ul>
 *
 * <p>注意:这里只用于审计 / drift 比对等"防碰撞需求低"的场景;**禁**用于密码 / 签名等强加密路径。
 */
public final class Hashes {

  private Hashes() {}

  /**
   * SHA-256 后取前 8 字节,返回 16 字符小写 hex 字符串。null / 空入参 → null。
   *
   * <p>SHA-256 算法不可用时(理论不会发生)返回 null,调用方需做 null-safe 处理。
   */
  public static String sha256Short(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      byte[] full =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      byte[] head = new byte[8];
      System.arraycopy(full, 0, head, 0, 8);
      return HexFormat.of().formatHex(head);
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }
}
