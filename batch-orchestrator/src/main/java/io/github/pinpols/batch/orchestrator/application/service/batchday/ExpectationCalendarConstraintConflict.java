package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.SQLException;

/** Identifies the two calendar identity foreign keys for stable application conflict mapping. */
final class ExpectationCalendarConstraintConflict {

  private ExpectationCalendarConstraintConflict() {}

  static boolean matches(Throwable failure, String constraintName) {
    for (Throwable current = failure; EmptyChecks.isNotNull(current); current = current.getCause()) {
      if (current instanceof SQLException sql
          && "23503".equals(sql.getSQLState())
          && sql.getMessage() != null
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
