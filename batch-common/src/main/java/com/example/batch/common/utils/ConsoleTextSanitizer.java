package com.example.batch.common.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * 控制台文本清洗工具类，用于对用户输入和页面展示内容进行安全处理。
 * {@code safeInput} 系列方法执行 NFKC Unicode 规范化、去除不可见控制字符并 strip 首尾空白，可选截断长度；
 * {@code safeDisplay} 系列方法在 {@code safeInput} 基础上追加 HTML 转义，防止 XSS。
 * 输入为 {@code null} 时所有方法均返回 {@code null}，不抛异常。
 */
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

  // ── R-4.16：链式 null-safe API ─────────────────────────────────────────
  // 静态 API 在需要组合多步清洗（如 "先 normalize，再 htmlEscape，再截断"）时
  // 要写两三层临时变量。改用 {@code ConsoleTextSanitizer.of(raw).normalize()
  // .htmlEscape().maxLength(64).get()} 一行表达意图，且 null 在整条链上安全透传。

  /** R-4.16：进入链式清洗。传 null 返回 null-safe 的 Chain（所有操作 no-op 直到 get()）。 */
  public static Chain of(String value) {
    return new Chain(value);
  }

  /** 链式清洗：每步返回新 Chain，null 安全透传；末尾用 {@link #get()} / {@link #orElse} 取值。 */
  public static final class Chain {
    private final String value;

    private Chain(String value) {
      this.value = value;
    }

    public Chain normalize() {
      return new Chain(ConsoleTextSanitizer.normalize(value));
    }

    public Chain htmlEscape() {
      return new Chain(value == null ? null : HtmlUtils.htmlEscape(value));
    }

    public Chain maxLength(int maxLength) {
      if (value == null || maxLength <= 0 || value.length() <= maxLength) {
        return this;
      }
      return new Chain(value.substring(0, maxLength));
    }

    public Chain strip() {
      return new Chain(value == null ? null : value.strip());
    }

    public String get() {
      return value;
    }

    public String orElse(String fallback) {
      return value == null ? fallback : value;
    }
  }
}
