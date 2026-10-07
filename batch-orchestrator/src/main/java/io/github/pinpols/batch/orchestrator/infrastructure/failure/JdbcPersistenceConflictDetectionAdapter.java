package io.github.pinpols.batch.orchestrator.infrastructure.failure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.failure.PersistenceConflictDetectionPort;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/** JDBC 唯一约束冲突探测适配器。 */
@Component
public class JdbcPersistenceConflictDetectionAdapter implements PersistenceConflictDetectionPort {

  private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

  @Override
  public boolean isUniqueConstraintViolation(Throwable throwable, String constraintName) {
    if (!Texts.hasText(constraintName)) {
      return false;
    }
    for (Throwable cursor = throwable; EmptyChecks.isNotNull(cursor); cursor = cursor.getCause()) {
      if (cursor instanceof SQLException sql) {
        if (UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
          return true;
        }
        if (Texts.hasText(sql.getMessage()) && sql.getMessage().contains(constraintName)) {
          return true;
        }
      }
    }
    return false;
  }
}
