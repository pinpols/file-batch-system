package io.github.pinpols.batch.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("JDBC异常边界:保持状态识别、外键约束定位与父引用失败原因")
class JdbcExceptionSupportTest {
  private static final String CONSTRAINT = "fk_calendar_reference";

  @Test
  @DisplayName("多层包装仍按SQLState识别业务日关闭,不依赖包装消息")
  void shouldRecognizeClosedBatchDay_whenSqlFailureIsWrapped() {
    Throwable failure = new IllegalStateException("outer", new SQLException("closed", "BDA01"));
    assertThat(JdbcExceptionSupport.hasSqlState(failure, "BDA01")).isTrue();
    assertThat(JdbcExceptionSupport.hasSqlState(failure, "23503")).isFalse();
  }

  @Test
  @DisplayName("仅展示消息含状态码不能伪装成数据库状态")
  void shouldRejectMessageOnlyState_whenNoSqlStateMatches() {
    Throwable failure = new IllegalStateException("BDA01", new SQLException("BDA01", "23505"));
    assertThat(JdbcExceptionSupport.hasSqlState(failure, "BDA01")).isFalse();
  }

  @Test
  @DisplayName("同一底层外键异常同时带状态和约束名时精确匹配")
  void shouldMatchExactForeignKey_whenStateAndNameShareSqlCause() {
    Throwable failure =
        new IllegalStateException(new SQLException("violates " + CONSTRAINT, "23503"));
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(failure, CONSTRAINT))
        .isTrue();
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(failure, "other_constraint"))
        .isFalse();
  }

  @Test
  @DisplayName("唯一约束等其他状态不能只因约束名相同而映射为外键失败")
  void shouldRejectOtherSqlState_whenConstraintNameMatches() {
    Throwable failure = new SQLException("violates " + CONSTRAINT, "23505");
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(failure, CONSTRAINT))
        .isFalse();
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolationInChain(failure, CONSTRAINT))
        .isFalse();
  }

  @Test
  @DisplayName("严格匹配不接受包装层约束名与底层状态拼成同一外键异常")
  void shouldRejectSplitIdentity_whenStrictMatchingIsRequired() {
    Throwable failure =
        new IllegalStateException(CONSTRAINT, new SQLException("reference missing", "23503"));
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(failure, CONSTRAINT))
        .isFalse();
  }

  @Test
  @DisplayName("补扫口径允许包装层约束名与底层外键状态分别出现")
  void shouldMatchSplitIdentity_whenCatchupChainMatchingIsRequired() {
    Throwable failure =
        new IllegalStateException(CONSTRAINT, new SQLException("reference missing", "23503"));
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolationInChain(failure, CONSTRAINT))
        .isTrue();
  }

  @Test
  @DisplayName("缺失异常或空数据库消息不产生外键命中")
  void shouldReturnFalse_whenFailureOrSqlMessageIsAbsent() {
    assertThat(JdbcExceptionSupport.hasSqlState(null, "BDA01")).isFalse();
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(null, CONSTRAINT))
        .isFalse();
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolationInChain(null, CONSTRAINT))
        .isFalse();
    assertThat(JdbcExceptionSupport.isForeignKeyConstraintViolation(
            new SQLException(null, "23503"), CONSTRAINT))
        .isFalse();
  }

  @Test
  @DisplayName("应用侧父引用失败保留数据完整性异常类型及独立的外键SQLState原因")
  void shouldPreserveForeignKeyCause_whenParentIsMissing() {
    DataIntegrityViolationException failure = JdbcExceptionSupport.missingReferenceViolation(
        "parent missing", "hot and cold parent missing");
    assertThat(failure).hasMessage("parent missing").hasCauseInstanceOf(SQLException.class);
    SQLException sqlCause = (SQLException) failure.getCause();
    assertThat(sqlCause.getMessage()).isEqualTo("hot and cold parent missing");
    assertThat(sqlCause.getSQLState()).isEqualTo("23503");
  }
}
