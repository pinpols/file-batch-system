package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.persistence.PostgresConstraintViolation;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/** 部分成功和租户失败结果使用稳定错误键；数据库诊断细节仅留在服务端日志。 */
final class TenantConfigFailureMessages {

  private TenantConfigFailureMessages() {}

  static String forFailure(Exception failure) {
    boolean dependencyRejected = PostgresConstraintViolation.from(failure)
        .map(violation -> violation.matches(
                "23503", "batch", "job_dependency", "fk_job_dependency_upstream")
            || violation.matches("23514", "batch", "job_dependency", "ck_job_dependency_acyclic"))
        .orElse(false);
    if (dependencyRejected) {
      return "error.common.invalid_argument";
    }
    // 事务或业务包装会改变最外层异常类型；不能因此把底层 SQL 诊断重新返回给调用方。
    for (Throwable cause = failure; EmptyChecks.isNotNull(cause); cause = cause.getCause()) {
      if (cause instanceof DataAccessException || cause instanceof SQLException) {
        return "error.common.system_error";
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return failure.getMessage();
  }
}
