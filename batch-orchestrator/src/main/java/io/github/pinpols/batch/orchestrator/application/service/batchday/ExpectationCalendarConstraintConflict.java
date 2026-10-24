package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.SQLException;

/** 用于稳定映射应用层冲突的两个日历标识外键。 */
final class ExpectationCalendarConstraintConflict {

  private ExpectationCalendarConstraintConflict() {}

  static boolean matches(Throwable failure, String constraintName) {
    for (Throwable current = failure;
        EmptyChecks.isNotNull(current);
        current = current.getCause()) {
      if (current instanceof SQLException sql
          && "23503".equals(sql.getSQLState())
          && EmptyChecks.isNotNull(sql.getMessage())
          && sql.getMessage().contains(constraintName)) {
        return true;
      }
      if (current == current.getCause()) {
        break;
      }
    }
    return false;
  }
}
