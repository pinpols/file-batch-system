package io.github.pinpols.batch.common.jdbc;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;

/** JDBC 异常的纯分类支持，应用层不直接识别或构造驱动异常。 */
public final class JdbcExceptionSupport {
  private static final String FOREIGN_KEY_SQL_STATE = "23503";

  private JdbcExceptionSupport() {}

  /** 按异常因果链识别数据库状态，不用展示消息替代状态判断。 */
  public static boolean hasSqlState(Throwable failure, String expectedSqlState) {
    for (Throwable current = failure;
        EmptyChecks.isNotNull(current);
        current = current.getCause()) {
      if (current instanceof SQLException sql && expectedSqlState.equals(sql.getSQLState())) {
        return true;
      }
      if (current == current.getCause()) {
        break;
      }
    }
    return false;
  }

  /** 状态和约束名必须在同一条 JDBC 异常上，防止把无关包装消息误映射为业务冲突。 */
  public static boolean isForeignKeyConstraintViolation(Throwable failure, String constraintName) {
    for (Throwable current = failure;
        EmptyChecks.isNotNull(current);
        current = current.getCause()) {
      if (current instanceof SQLException sql
          && FOREIGN_KEY_SQL_STATE.equals(sql.getSQLState())
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

  /** 补扫控制保留包装层约束名与底层外键状态分布在因果链不同层的识别口径。 */
  public static boolean isForeignKeyConstraintViolationInChain(
      Throwable failure, String constraintName) {
    boolean foreignKeyViolation = false;
    boolean expectedConstraint = false;
    for (Throwable current = failure;
        EmptyChecks.isNotNull(current);
        current = current.getCause()) {
      if (current instanceof SQLException sql && FOREIGN_KEY_SQL_STATE.equals(sql.getSQLState())) {
        foreignKeyViolation = true;
      }
      if (EmptyChecks.isNotNull(current.getMessage())
          && current.getMessage().contains(constraintName)) {
        expectedConstraint = true;
      }
      if (current == current.getCause()) {
        break;
      }
    }
    return foreignKeyViolation && expectedConstraint;
  }

  /** 对热/冷父记录均不存在的应用校验保留数据库外键失败的可识别原因。 */
  public static DataIntegrityViolationException missingReferenceViolation(
      String message, String causeMessage) {
    return new DataIntegrityViolationException(
        message, new SQLException(causeMessage, FOREIGN_KEY_SQL_STATE));
  }
}
