package io.github.pinpols.batch.console.infrastructure.excel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 单 sheet 配置导入的单元格解析：规范化文本后校验，问题累积到 {@code issues}，不在解析阶段抛错。 */
final class SingleSheetExcelCellParsers {

  private SingleSheetExcelCellParsers() {}

  static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  static String requireText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
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

  static String optionalText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  static String requireEnum(
      Map<String, String> values,
      String key,
      Set<String> allowed,
      int maxLength,
      List<String> issues) {
    String normalized = requireText(values, key, maxLength, issues);
    if (EmptyChecks.isNull(normalized)) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      issues.add(key + " must be one of " + allowed);
    }
    return upper;
  }

  /** 可选枚举:留空回退 {@code defaultValue}(对齐 DB 列 DEFAULT),非空时按 {@code allowed} 校验。 */
  static String optionalEnum(
      Map<String, String> values,
      String key,
      Set<String> allowed,
      int maxLength,
      String defaultValue,
      List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      normalized = normalized.substring(0, maxLength);
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      issues.add(key + " must be one of " + allowed);
    }
    return upper;
  }

  static Integer optionalInteger(
      Map<String, String> values, String key, int min, int defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(normalized);
      if (value < min) {
        issues.add(key + " must be >= " + min);
      }
      return value;
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          SingleSheetExcelCellParsers.class, "catch:NumberFormatException", e);

      issues.add(key + " must be integer");
      return defaultValue;
    }
  }

  static Integer requireInteger(
      Map<String, String> values, String key, int min, List<String> issues) {
    String normalized = normalize(values.get(key));
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
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          SingleSheetExcelCellParsers.class, "catch:NumberFormatException", e);

      issues.add(key + " must be integer");
      return min;
    }
  }

  static Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (List.of("TRUE", "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of("FALSE", "N", "0", "NO").contains(upper)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  /** 租户字段解析：空则用当前租户，不匹配则报错。 */
  static String resolveTenantField(
      Map<String, String> values, String tenantId, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!Texts.hasText(effectiveTenant)) {
      return tenantId;
    }
    if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return effectiveTenant;
  }

  /** 可选 JSON 字段：非空时校验 JSON 合法性。 */
  static String optionalJson(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException e) {
      SwallowedExceptionLogger.info(
          SingleSheetExcelCellParsers.class, "catch:IllegalArgumentException", e);

      issues.add(key + " must be valid JSON");
      return normalized;
    }
  }
}
