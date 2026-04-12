package com.example.batch.common.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

public final class ConsoleTextSanitizer {

  private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

  private ConsoleTextSanitizer() {}

  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
    normalized = CONTROL_CHARS.matcher(normalized).replaceAll("");
    return normalized.strip();
  }

  public static String safeInput(String value) {
    return normalize(value);
  }

  public static String safeInput(String value, int maxLength) {
    String normalized = normalize(value);
    if (normalized == null) {
      return null;
    }
    if (maxLength > 0 && normalized.length() > maxLength) {
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  public static String safeDisplay(String value) {
    String normalized = normalize(value);
    if (normalized == null) {
      return null;
    }
    return HtmlUtils.htmlEscape(normalized);
  }

  public static String safeDisplay(String value, int maxLength) {
    String normalized = safeInput(value, maxLength);
    if (normalized == null) {
      return null;
    }
    return HtmlUtils.htmlEscape(normalized);
  }
}
