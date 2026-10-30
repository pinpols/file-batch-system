package io.github.pinpols.batch.console.shared.query;

import java.sql.Date;
import java.time.LocalDate;

final class ConsoleSqlDateSupport {

  private ConsoleSqlDateSupport() {}

  static boolean isSqlDate(Object value) {
    return value instanceof Date;
  }

  static LocalDate toLocalDate(Object value) {
    return ((Date) value).toLocalDate();
  }
}
