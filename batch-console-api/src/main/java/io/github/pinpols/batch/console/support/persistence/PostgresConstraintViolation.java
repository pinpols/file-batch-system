package io.github.pinpols.batch.console.support.persistence;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;

/** 从驱动结构化字段识别数据库约束，避免依赖本地化SQL错误文本；不引入编译期PostgreSQL驱动依赖。 */
public record PostgresConstraintViolation(
    String sqlState, String schema, String table, String constraint) {

  public static Optional<PostgresConstraintViolation> from(Throwable failure) {
    if (EmptyChecks.isNull(failure)) {
      return Optional.empty();
    }
    Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
    if (!(root instanceof SQLException sql)
        || !"org.postgresql.util.PSQLException".equals(root.getClass().getName())
        || EmptyChecks.isBlank(sql.getSQLState())) {
      return Optional.empty();
    }
    try {
      Object serverError = root.getClass().getMethod("getServerErrorMessage").invoke(root);
      if (EmptyChecks.isNull(serverError)) {
        return Optional.empty();
      }
      String sqlState = sql.getSQLState();
      String schema = field(serverError, "getSchema");
      String table = field(serverError, "getTable");
      String constraint = field(serverError, "getConstraint");
      PostgresConstraintViolation violation =
          new PostgresConstraintViolation(sqlState, schema, table, constraint);
      return Optional.of(violation);
    } catch (ReflectiveOperationException failureToRead) {
      SwallowedExceptionLogger.info(
          PostgresConstraintViolation.class, "catch:ReflectiveOperationException", failureToRead);
      return Optional.empty();
    }
  }

  public boolean matches(String state, String schemaName, String tableName, String constraintName) {
    return Objects.equals(sqlState, state)
        && Objects.equals(schema, schemaName)
        && Objects.equals(table, tableName)
        && Objects.equals(constraint, constraintName);
  }

  private static String field(Object serverError, String method)
      throws ReflectiveOperationException {
    Object fieldValue = serverError.getClass().getMethod(method).invoke(serverError);
    return fieldValue instanceof String text ? text : null;
  }
}
