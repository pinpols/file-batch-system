package io.github.pinpols.batch.common.utils;

/**
 * HTTP correlation id normalization for requestId / traceId headers.
 *
 * <p>Database columns and response headers both expect bounded, header-safe values. External
 * callers may still send arbitrary header bytes, so normalize at ingress and keep downstream MDC /
 * audit / internal REST propagation consistent.
 */
public final class CorrelationIds {

  public static final int MAX_HEADER_ID_LENGTH = 128;

  private CorrelationIds() {}

  public static String normalize(String value, String fallback) {
    String normalized = normalize(value);
    if (normalized == null || normalized.isBlank()) {
      return fallback;
    }
    return normalized;
  }

  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_HEADER_ID_LENGTH));
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
