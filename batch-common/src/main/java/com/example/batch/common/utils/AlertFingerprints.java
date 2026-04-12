package com.example.batch.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 用于告警去重的稳定指纹（租户 + 类型 + 资源范围），最长 128 字符。 */
public final class AlertFingerprints {

  private AlertFingerprints() {}

  public static String build(String tenantId, String alertType, String resourceKey) {
    String tenant = tenantId == null ? "" : tenantId;
    String type = alertType == null ? "" : alertType;
    String resource = resourceKey == null ? "" : resourceKey;
    String raw = tenant + "|" + type + "|" + resource;
    return sha256Hex(raw).substring(0, 64);
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
