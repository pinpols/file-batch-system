package io.github.pinpols.batch.console.support.excel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Excel 单 sheet "读 + 解析 + 返回值" 风格的行解析工具。
 *
 * <p>与 {@link SheetValidationHelpers}（"传值 + 收集 issue"）的区别：
 *
 * <ul>
 *   <li>本工具：调用方持 {@code Map<String, String> values}（一行的列名→原始值），按列名读取并自动 normalize、长度截断 / 范围校验，
 *       返回解析好的值（可空），同时把违规追加到 {@code issues}。
 *   <li>{@link SheetValidationHelpers}：调用方已持有 normalize 过的标量值，仅追加 issue，不返回值。
 * </ul>
 *
 * <p>用于消除 {@code DefaultConsoleBusinessCalendarExcelApplicationService} 与 {@code
 * DefaultConsolePipelineDefinitionExcelApplicationService} 此前各自一份的 5/6 重叠 helper 家族。
 */
public final class ExcelMapRowReader {

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final List<String> TRUE_TOKENS = List.of("TRUE", "Y", "1", "YES");
  private static final List<String> FALSE_TOKENS = List.of("FALSE", "N", "0", "NO");

  private ExcelMapRowReader() {}

  /** 必填文本：blank → "key is required"；超长 → 截断 + "key too long"。 */
  public static String requireText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  /** 选填文本：blank → null（不报错）；超长 → 截断 + "key too long"。 */
  public static String optionalText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  /** 必填枚举：先走 requireText，再做 UPPERCASE + allowlist 比对，命中则返回 UPPERCASE 值。 */
  public static String requireEnum(
      Map<String, String> values,
      String key,
      Set<String> allowed,
      int maxLength,
      List<String> issues) {
    String normalized = requireText(values, key, maxLength, issues);
    if (normalized == null) {
      return null;
    }
    String normalizedUpper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalizedUpper)) {
      issues.add(key + " must be one of " + allowed);
    }
    return normalizedUpper;
  }

  /** 必填整数（带最小值回退）：blank / 非整数 → 返回 min 并报错；&lt; min → 报错但仍返回原值。 */
  public static Integer requireInteger(
      Map<String, String> values, String key, int min, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return min;
    }
    try {
      int value = Integer.parseInt(normalized);
      if (value < min) {
        issues.add(key + " must be >= " + min);
      }
      return value;
    } catch (NumberFormatException exception) {
      SwallowedExceptionLogger.info(
          ExcelMapRowReader.class, "catch:NumberFormatException", exception);

      issues.add(key + " must be integer");
      return min;
    }
  }

  /** 选填布尔：TRUE/Y/1/YES → true，FALSE/N/0/NO → false，其他 → 报错并默认回退值；blank → 默认值。 */
  public static Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (TRUE_TOKENS.contains(upper)) {
      return true;
    }
    if (FALSE_TOKENS.contains(upper)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  /** 必填日期（yyyy-MM-dd）：blank / 解析失败 → 返回 null 并报错。 */
  public static LocalDate requireDate(Map<String, String> values, String key, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    try {
      return LocalDate.parse(normalized, ISO_DATE);
    } catch (DateTimeParseException exception) {
      SwallowedExceptionLogger.info(
          ExcelMapRowReader.class, "catch:DateTimeParseException", exception);

      issues.add(key + " must be date in yyyy-MM-dd format");
      return null;
    }
  }

  /** 选填 JSON 文本：blank → null；解析失败 → 报错但返回原文（保留以便上层观察）。 */
  public static String optionalJson(Map<String, String> values, String key, List<String> issues) {
    String normalized = ConsoleTextSanitizer.normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          ExcelMapRowReader.class, "catch:IllegalArgumentException", exception);

      issues.add(key + " must be valid JSON");
      return normalized;
    }
  }
}
