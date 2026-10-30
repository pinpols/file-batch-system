package io.github.pinpols.batch.console.support.excel;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;

/** Excel 校验规则共用的标量转换辅助方法。 */
public final class ConsoleExcelValueSupport {

  private ConsoleExcelValueSupport() {}

  public static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public static String upper(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String trimmedValue = value.trim();
    return trimmedValue.toUpperCase(Locale.ROOT);
  }

  public static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public static int parseRowNo(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
