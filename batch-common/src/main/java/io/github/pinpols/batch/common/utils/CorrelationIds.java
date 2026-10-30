package io.github.pinpols.batch.common.utils;

import javax.annotation.Nullable;

/**
 * HTTP requestId / traceId 请求头的关联 ID 规范化处理。
 *
 * <p>Database columns and response headers both expect bounded, header-safe values. External
 * 调用方仍可能发送任意请求头字节，因此应在入口处完成规范化，并保持下游 MDC /
 * audit / internal REST propagation consistent.
 */
public final class CorrelationIds {

  public static final int MAX_HEADER_ID_LENGTH = 128;

  private CorrelationIds() {}

  public static String normalize(@Nullable String value, String fallback) {
    String normalized = normalize(value);
    if (normalized == null || normalized.isBlank()) {
      return fallback;
    }
    return normalized;
  }

  @Nullable
  public static String normalize(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    int initialCapacity = Math.min(value.length(), MAX_HEADER_ID_LENGTH);
    StringBuilder out = new StringBuilder(initialCapacity);
    String trimmed = value.trim();
    for (int i = 0; i < trimmed.length() && out.length() < MAX_HEADER_ID_LENGTH; i++) {
      char c = trimmed.charAt(i);
      if (isAllowed(c)) {
        out.append(c);
      } else if (!Character.isISOControl(c)) {
        out.append('_');
      }
    }
    return out.isEmpty() ? null : out.toString();
  }

  private static boolean isAllowed(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_'
        || c == '.'
        || c == ':';
  }
}
