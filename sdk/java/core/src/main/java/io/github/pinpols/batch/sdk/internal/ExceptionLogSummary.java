package io.github.pinpols.batch.sdk.internal;

import java.util.regex.Pattern;

/** SDK 内部异常日志摘要，避免租户异常消息注入多行日志或泄漏常见凭据。 */
public final class ExceptionLogSummary {

  static final int MAX_LENGTH = 512;

  private static final String MASK = "****";
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+");
  private static final String SENSITIVE_KEYS =
      "(?:password|secret|token|api[_-]?key|authorization|private[_-]?key|credential)";
  private static final Pattern QUOTED_SENSITIVE_ASSIGNMENT = Pattern.compile(
      "(?i)([\\\"']?" + SENSITIVE_KEYS + "[\\\"']?\\s*[:=]\\s*)" + "(\\\"[^\\\"]*\\\"|'[^']*')");
  private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile("(?i)([\\\"']?"
      + SENSITIVE_KEYS + "[\\\"']?\\s*[:=]\\s*)" + "(?:Bearer\\s+[-\\w.~+/=]+|[^\\s,;}&]+)");
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[-\\w.~+/=]+");
  private static final Pattern URI_USER_INFO =
      Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)([^/@\\s:]+):([^/@\\s]+)@");

  private ExceptionLogSummary() {}

  public static String of(Throwable throwable) {
    if (throwable == null) { // empty-check: allow - SDK core 不依赖平台 common 工具类
      return "(null)";
    }
    String message = sanitize(throwable.getMessage());
    String value = throwable.getClass().getSimpleName()
        + (message.isBlank() ? "" : ": " + message); // empty-check: allow - SDK 保持 JDK-only
    return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH - 3) + "...";
  }

  private static String sanitize(String message) {
    if (message == null || message.isBlank()) { // empty-check: allow - SDK 保持 JDK-only
      return "";
    }
    String controlCharactersReplaced = CONTROL_CHARACTERS.matcher(message).replaceAll(" ");
    String singleLine = controlCharactersReplaced.trim();
    String credentialsMasked =
        QUOTED_SENSITIVE_ASSIGNMENT.matcher(singleLine).replaceAll("$1" + MASK);
    credentialsMasked = SENSITIVE_ASSIGNMENT.matcher(credentialsMasked).replaceAll("$1" + MASK);
    credentialsMasked = BEARER_TOKEN.matcher(credentialsMasked).replaceAll("Bearer " + MASK);
    return URI_USER_INFO.matcher(credentialsMasked).replaceAll("$1" + MASK + "@");
  }
}
