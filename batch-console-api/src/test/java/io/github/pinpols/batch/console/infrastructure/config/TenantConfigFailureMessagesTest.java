package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("配置导入数据库失败结果：精确识别依赖约束并隐藏内部诊断")
class TenantConfigFailureMessagesTest {

  @ParameterizedTest
  @CsvSource({"23503, fk_job_dependency_upstream", "23514, ck_job_dependency_acyclic"})
  @DisplayName("已知依赖外键及图环约束返回稳定参数错误键")
  void shouldMapKnownDependencyConstraints(String state, String constraint) {
    var failure = databaseFailure(state, "batch", "job_dependency", constraint);

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.common.invalid_argument");
  }

  @ParameterizedTest
  @CsvSource({
    "23514, batch, job_dependency, fk_job_dependency_upstream",
    "23503, other_schema, job_dependency, fk_job_dependency_upstream",
    "23503, batch, other_table, fk_job_dependency_upstream",
    "23503, batch, job_dependency, other_constraint"
  })
  @DisplayName("SQLSTATE或引用身份任一不符都不能误分类为已知依赖错误")
  void shouldHideUnknownConstraintDetails(
      String state, String schema, String table, String constraint) {
    var failure = databaseFailure(state, schema, table, constraint);

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.common.system_error");
  }

  @Test
  @DisplayName("业务包装下的已知数据库拒绝仍使用结构化字段")
  void shouldReadStructuredFieldsThroughWrappers() {
    var databaseFailure =
        databaseFailure("23503", "batch", "job_dependency", "fk_job_dependency_upstream");
    var failure = new IllegalStateException("operation failed", databaseFailure);

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.common.invalid_argument");
  }

  @Test
  @DisplayName("无结构化字段时不根据SQL文本猜测已知依赖约束")
  void shouldNotClassifyFromSqlMessage() {
    var failure = new DataIntegrityViolationException(
        "SQL /private/Mapper.xml fk_job_dependency_upstream ck_job_dependency_acyclic");

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.common.system_error");
  }

  @Test
  @DisplayName("未知数据库错误经过业务包装也不透出SQL或路径")
  void shouldHideWrappedUnknownDatabaseFailure() {
    var databaseFailure = new DataIntegrityViolationException(
        "SQL /private/Mapper.xml", new SQLException("internal SQL", "XX000"));
    var failure = new IllegalStateException(databaseFailure);

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.common.system_error");
  }

  @Test
  @DisplayName("非数据库业务校验继续保留原有错误键")
  void shouldPreserveBusinessValidationMessage() {
    var failure = BizException.of(ResultCode.INVALID_ARGUMENT, "error.calendar.not_found");

    assertThat(TenantConfigFailureMessages.forFailure(failure))
        .isEqualTo("error.calendar.not_found");
  }

  private static DataIntegrityViolationException databaseFailure(
      String state, String schema, String table, String constraint) {
    String fields = "SERROR\u0000C" + state + "\u0000Mlocalized database rejection\u0000s" + schema
        + "\u0000t" + table + "\u0000n" + constraint + "\u0000\u0000";
    return new DataIntegrityViolationException(
        "SQL /private/Mapper.xml", new PSQLException(new ServerErrorMessage(fields)));
  }
}
