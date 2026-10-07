package io.github.pinpols.batch.orchestrator.infrastructure.failure;

import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.failure.TechnicalFailureClassificationPort;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/** JDBC、Spring DAO 与 HTTP 客户端异常的基础设施分类适配器。 */
@Component
public class SpringTechnicalFailureClassificationAdapter
    implements TechnicalFailureClassificationPort {

  @Override
  public FailureClass classify(Throwable throwable) {
    for (Throwable cursor = throwable; EmptyChecks.isNotNull(cursor); cursor = cursor.getCause()) {
      if (cursor instanceof TimeoutException
          || cursor instanceof QueryTimeoutException
          || cursor instanceof SQLTimeoutException) {
        return FailureClass.TIMEOUT;
      }
      if (cursor instanceof TransientDataAccessException
          || cursor instanceof SQLTransientException
          || cursor instanceof OptimisticLockingFailureException
          || cursor instanceof ResourceAccessException) {
        return FailureClass.INFRASTRUCTURE;
      }
      if (cursor instanceof DataIntegrityViolationException) {
        return FailureClass.DATA_QUALITY;
      }
      if (cursor instanceof SQLException sql && Texts.hasText(sql.getSQLState())) {
        return classifySqlState(sql.getSQLState());
      }
      if (cursor instanceof NonTransientDataAccessException) {
        return FailureClass.DATA_QUALITY;
      }
      if (cursor instanceof DataAccessException) {
        return FailureClass.INFRASTRUCTURE;
      }
    }
    return FailureClass.UNKNOWN;
  }

  private FailureClass classifySqlState(String sqlState) {
    if (sqlState.startsWith("08")
        || sqlState.startsWith("40")
        || sqlState.startsWith("53")
        || sqlState.startsWith("57")
        || sqlState.startsWith("58")) {
      return FailureClass.INFRASTRUCTURE;
    }
    if (sqlState.startsWith("22") || sqlState.startsWith("23")) {
      return FailureClass.DATA_QUALITY;
    }
    if (sqlState.startsWith("42")) {
      return FailureClass.CONFIG;
    }
    return FailureClass.UNKNOWN;
  }
}
