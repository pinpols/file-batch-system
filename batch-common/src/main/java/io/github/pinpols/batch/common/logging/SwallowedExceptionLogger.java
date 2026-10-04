package io.github.pinpols.batch.common.logging;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * 写<strong>单行摘要</strong>日志（类型 + message），不显式打印完整栈，避免与 {@code log.error("...", t)} 默认打出整栈的行为混用。
 *
 * <p><strong>级别：</strong>{@link #info} 预期 fallback；{@link #warn} 捕获并抑制但仍可疑；{@link #error}
 * 确认为故障且当前分支仍需捕获处理（少用）。一般故障仍应<strong>抛出</strong>，由全局异常处理统一记录。
 *
 * <p>{@link LoggerFactory#getLogger(Class)} 按调用类名作 logger，便于按包调级别。
 */
public final class SwallowedExceptionLogger {

  static final int MAX_SUMMARY_LENGTH = 512;

  private static final String MASK = "****";
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+");
  private static final Pattern SENSITIVE_ASSIGNMENT =
      Pattern.compile("(?i)([\\\"']?(?:password|secret|token|api[_-]?key|apikey|authorization|"
          + "private[_-]?key|credential)[\\\"']?\\s*[:=]\\s*)"
          + "(?:\\\"[^\\\"]*\\\"|'[^']*'|Bearer\\s+[A-Za-z0-9._~+/=-]+|[^\\s,;}&]+)");
  private static final Pattern BEARER_TOKEN =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern URI_USER_INFO =
      Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)([^/@\\s:]+):([^/@\\s]+)@");

  private SwallowedExceptionLogger() {}

  /** 捕获并抑制但仍需关注（单行摘要，无栈）。预期 fallback 请用 {@link #info}；确认故障语义请抛异常或 {@link #error}。 */
  public static void warn(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).warn("{}: {}", where, summary(t));
  }

  /** 预期 fallback / 常见解析失败等（单行摘要，无栈）。 */
  public static void info(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).info("{}: {}", where, summary(t));
  }

  /** 确认为故障且必须在本分支捕获处理时（单行摘要，无栈）。优先仍建议抛出由上层统一记录。 */
  public static void error(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).error("{}: {}", where, summary(t));
  }

  /**
   * 返回可安全写入单行日志的异常摘要。
   *
   * <p>摘要统一移除换行和控制字符、遮蔽常见凭据并限制长度。需要完整堆栈的未预期故障不应调用本方法，
   * 而应将 {@link Throwable} 作为 SLF4J 最后一个参数传入。
   */
  public static String summary(Throwable t) {
    if (EmptyChecks.isNull(t)) {
      return "(null)";
    }
    String type = t.getClass().getSimpleName();
    String message = sanitize(t.getMessage());
    String value = type + (EmptyChecks.isBlank(message) ? "" : ": " + message);
    if (value.length() <= MAX_SUMMARY_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
  }

  private static String sanitize(String message) {
    if (EmptyChecks.isBlank(message)) {
      return "";
    }
    String singleLine = CONTROL_CHARACTERS.matcher(message).replaceAll(" ").trim();
    String credentialsMasked = SENSITIVE_ASSIGNMENT.matcher(singleLine).replaceAll("$1" + MASK);
    credentialsMasked = BEARER_TOKEN.matcher(credentialsMasked).replaceAll("Bearer " + MASK);
    return URI_USER_INFO.matcher(credentialsMasked).replaceAll("$1" + MASK + "@");
  }
}
