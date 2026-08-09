package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.Locale;
import java.util.Set;

/** 配置包 Excel 行值规范化/解析的共享静态工具（解析与 apply 两侧共用）。 */
@SuppressWarnings("java:S2583")
final class TenantConfigPackageExcelValueSupport {

  private TenantConfigPackageExcelValueSupport() {}

  static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  static String normalizeEnum(String value) {
    String n = normalize(value);
    return n == null ? null : n.toUpperCase(Locale.ROOT);
  }

  static Integer parseInteger(String value) {
    String n = normalize(value);
    if (!Texts.hasText(n)) {
      return null;
    }
    try {
      return Integer.parseInt(n);
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          TenantConfigPackageExcelValueSupport.class, "catch:NumberFormatException", e);

      return null;
    }
  }

  static Boolean parseBoolean(String value, Boolean defaultValue) {
    String n = normalize(value);
    if (!Texts.hasText(n)) {
      return defaultValue;
    }
    String upper = n.toUpperCase(Locale.ROOT);
    if (Set.of("TRUE", "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (Set.of("FALSE", "N", "0", "NO").contains(upper)) {
      return false;
    }
    return defaultValue;
  }

  static String safeOp(String operatorId) {
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
  }
}
