package com.example.batch.common.i18n;

import com.example.batch.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Locale;

/** {@link BizException} ↔ {@link LocalizedError} 互转工具,集中持久化 i18n 错误的写入路径。 */
public final class BizExceptionUtils {

  private BizExceptionUtils() {}

  /**
   * 把异常拆成 {@link LocalizedError} 三元组以便持久化:
   *
   * <ul>
   *   <li>{@link BizException#of(com.example.batch.common.enums.ResultCode, String, Object...)}
   *       构造的:返回 {@code (key, argsJson, resolver.resolve(ex))}
   *   <li>历史 literal {@code BizException(code, message)} 或第三方异常:返回 {@code (null, null,
   *       throwable.getMessage())},写入仅靠 renderedMessage 列展示
   * </ul>
   *
   * <p>{@code resolver} 为 null 时不渲染,只留 key + args + 异常原文 message,后续读路径 fallback。
   */
  public static LocalizedError toLocalizedError(
      Throwable throwable, BizMessageResolver resolver, ObjectMapper objectMapper) {
    if (throwable == null) {
      return LocalizedError.EMPTY;
    }
    if (throwable instanceof BizException biz && biz.getMessageKey() != null) {
      String argsJson = serializeArgs(biz.getMessageArgs(), objectMapper);
      String rendered = resolver == null ? biz.getMessage() : resolver.resolve(biz);
      return new LocalizedError(biz.getMessageKey(), argsJson, rendered);
    }
    return new LocalizedError(null, null, throwable.getMessage());
  }

  /** 仅传 message 的简化构造:用于第三方异常或 literal 字符串。 */
  public static LocalizedError ofLiteral(String message) {
    return new LocalizedError(null, null, message);
  }

  /** 直接构造 i18n 三元组,callsite 自己组织 key + args 时用。 */
  public static LocalizedError of(
      String key, Object[] args, String renderedMessage, ObjectMapper objectMapper) {
    return new LocalizedError(key, serializeArgs(args, objectMapper), renderedMessage);
  }

  /** 把持久化的 {@code error_args} JSONB 列(字符串)反序列化为 Object[]。null / 非法 JSON 返回空数组,渲染时占位符 {0} 留空字符串。 */
  public static Object[] parseArgs(String argsJson, ObjectMapper objectMapper) {
    if (argsJson == null || argsJson.isBlank() || objectMapper == null) {
      return new Object[0];
    }
    try {
      Object[] parsed = objectMapper.readValue(argsJson, Object[].class);
      return parsed == null ? new Object[0] : parsed;
    } catch (Exception ignored) {
      return new Object[0];
    }
  }

  /** 渲染单个 key + args 为当前 Locale 字符串。{@code resolver} / {@code key} 为空时返回 fallback。 */
  public static String renderOrFallback(
      BizMessageResolver resolver, String key, Object[] args, String fallback, Locale locale) {
    if (resolver == null || key == null || key.isBlank()) {
      return fallback;
    }
    BizException synthetic =
        BizException.of(com.example.batch.common.enums.ResultCode.SYSTEM_ERROR, key, args);
    String rendered = resolver.resolve(synthetic, locale);
    if (rendered == null || rendered.isBlank() || rendered.equals(key)) {
      return fallback;
    }
    return rendered;
  }

  private static String serializeArgs(Object[] args, ObjectMapper objectMapper) {
    if (args == null || args.length == 0 || objectMapper == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(Arrays.asList(args));
    } catch (Exception ignored) {
      return null;
    }
  }
}
