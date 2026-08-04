package io.github.pinpols.batch.console.support.excel;

import java.util.Locale;

/** Shared scalar conversion helpers for Excel validation rules. */
public final class ConsoleExcelValueSupport {

  private ConsoleExcelValueSupport() {}

  public static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public static String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
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
