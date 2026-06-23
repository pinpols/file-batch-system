package io.github.pinpols.batch.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** {@link BizException} ↔ {@link LocalizedError} 互转工具,集中持久化 i18n 错误的写入路径。 */
@Slf4j
public final class BizExceptionUtils {

  /**
   * error_message 写入上限：取所有相关表 VARCHAR(N) 列里最小值（{@code file_error_record} / {@code
   * event_outbox_logs} 等是 1024，{@code job_task} / {@code workflow_node_run} 是 2048）。 写入前统一截断到此值 +
   * 截断标记 {@value #TRUNCATION_SUFFIX}，避免某些表写超长触发 PG {@code value too long for type character
   * varying} 整事务回滚。即使目标表是 2048 / TEXT，多截一些也无损可读性（业务异常 message 通常 100~300 字符）。
   */
  static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

  private static final String TRUNCATION_SUFFIX = "…[truncated]";

  private BizExceptionUtils() {}

  /**
   * 把异常拆成 {@link LocalizedError} 三元组以便持久化:
   *
   * <ul>
   *   <li>{@link BizException#of(io.github.pinpols.batch.common.enums.ResultCode, String,
   *       Object...)} 构造的:返回 {@code (key, argsJson, resolver.resolve(ex))}
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
      return new LocalizedError(biz.getMessageKey(), argsJson, truncate(rendered));
    }
    return new LocalizedError(null, null, truncate(throwable.getMessage()));
  }

  /** 仅传 message 的简化构造:用于第三方异常或 literal 字符串。 */
  public static LocalizedError ofLiteral(String message) {
    return new LocalizedError(null, null, truncate(message));
  }

  /** 直接构造 i18n 三元组,callsite 自己组织 key + args 时用。 */
  public static LocalizedError of(
      String key, Object[] args, String renderedMessage, ObjectMapper objectMapper) {
    return new LocalizedError(key, serializeArgs(args, objectMapper), truncate(renderedMessage));
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
      SwallowedExceptionLogger.warn(BizExceptionUtils.class, "catch:Exception", ignored);

      return new Object[0];
    }
  }

  /** 渲染单个 key + args 为当前 Locale 字符串。{@code resolver} / {@code key} 为空时返回 fallback。 */
  public static String renderOrFallback(
      BizMessageResolver resolver, String key, Object[] args, String fallback, Locale locale) {
    if (resolver == null || key == null || key.isBlank()) {
      return fallback;
    }
    BizException synthetic = BizException.of(ResultCode.SYSTEM_ERROR, key, args);
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
    warnOnComplexArgs(args);
    try {
      return objectMapper.writeValueAsString(Arrays.asList(args));
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(BizExceptionUtils.class, "catch:Exception", ignored);

      return null;
    }
  }

  /**
   * 开发期警告：args 被设计为占位符替换的标量（String / Number / Boolean / 等），传入 Throwable / Map / Collection
   * 等复杂对象会序列化为 {@code {"key":...}} / {@code [..]} 字面量，渲染时 toString 出来污染前端文案。生产仍正常写入数据库（不抛），仅
   * log.warn 提示开发期排查 callsite。
   */
  private static void warnOnComplexArgs(Object[] args) {
    for (Object arg : args) {
      if (arg == null
          || arg instanceof CharSequence
          || arg instanceof Number
          || arg instanceof Boolean
          || arg instanceof Enum<?>) {
        continue;
      }
      if (arg instanceof Throwable
          || arg instanceof Map<?, ?>
          || arg instanceof Collection<?>
          || arg.getClass().isArray()) {
        log.warn(
            "i18n error_args contains complex type {} — placeholder substitution will produce"
                + " toString() literal; pass scalars only",
            arg.getClass().getName());
        return; // 一次警告即可，避免噪声
      }
    }
  }

  /**
   * 截断 {@code error_message} 到 {@link #ERROR_MESSAGE_MAX_LENGTH}，保留前缀 + 末尾 {@value
   * #TRUNCATION_SUFFIX} 提示。null / 短串原样返回。
   */
  static String truncate(String message) {
    if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
      return message;
    }
    int keep = ERROR_MESSAGE_MAX_LENGTH - TRUNCATION_SUFFIX.length();
    return message.substring(0, keep) + TRUNCATION_SUFFIX;
  }
}
