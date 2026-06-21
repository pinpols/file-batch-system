package com.example.batch.common.logging;

import org.slf4j.LoggerFactory;

/**
 * 写<strong>单行摘要</strong>日志（类型 + message），不显式打印完整栈，避免与 {@code log.error("...", t)} 默认打出整栈的行为混用。
 *
 * <p><strong>级别：</strong>{@link #info} 预期 fallback；{@link #warn} 捕获并抑制但仍可疑；{@link #error}
 * 真故障语义且当前仍需捕获处理（少用）。一般「真异常」仍应<strong>抛出</strong>，由全局异常处理统一记录。
 *
 * <p>{@link LoggerFactory#getLogger(Class)} 按调用类名作 logger，便于按包调级别。
 */
public final class SwallowedExceptionLogger {

  private SwallowedExceptionLogger() {}

  /** 捕获并抑制但仍值得多看一眼（单行摘要，无栈）。预期 fallback 请用 {@link #info}；确认真故障语义请抛异常或 {@link #error}。 */
  public static void warn(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).warn("{}: {}", where, safeSummary(t));
  }

  /** 预期 fallback / 常见解析失败等（单行摘要，无栈）。 */
  public static void info(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).info("{}: {}", where, safeSummary(t));
  }

  /** 真故障语义且必须在本分支捕获处理时（单行摘要，无栈）。优先仍建议抛出由上层统一记录。 */
  public static void error(Class<?> category, String where, Throwable t) {
    LoggerFactory.getLogger(category).error("{}: {}", where, safeSummary(t));
  }

  private static String safeSummary(Throwable t) {
    if (t == null) {
      return "(null)";
    }
    String msg = t.getMessage();
    return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
  }
}
