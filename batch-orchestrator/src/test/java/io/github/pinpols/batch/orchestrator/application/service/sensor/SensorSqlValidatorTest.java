package io.github.pinpols.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensorSqlValidatorTest {

  private static final List<String> ALLOWED = List.of("biz");

  @Test
  void validate_simpleSelect_passes() {
    String sql = "SELECT id FROM biz.signal WHERE status = :status LIMIT 1";
    assertThat(SensorSqlValidator.validate(sql, ALLOWED)).isEqualTo(sql);
  }

  @Test
  void validate_blankSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("  ", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void validateUpdateThrows() {
    assertThatThrownBy(
            () -> SensorSqlValidator.validate("UPDATE biz.signal SET status='X'", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT/WITH");
  }

  @Test
  void validate_selectStar_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT * FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  void validate_disallowedSchema_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM hr.payroll", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disallowed schema");
  }

  @Test
  void validate_unqualifiedTable_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema.table");
  }

  @Test
  void validate_garbageSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("this is not sql", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parse error");
  }

  // ── S3: 禁用函数黑名单 ────────────────────────────────────────────────────
  @Test
  void validate_pgSleep_throws() {
    assertThatThrownBy(
            () -> SensorSqlValidator.validate("SELECT pg_sleep(10) FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep");
  }

  @Test
  void validate_pgReadFile_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT pg_read_file('/etc/passwd') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_read_file");
  }

  @Test
  void validate_dblinkInSubquery_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT id FROM biz.signal WHERE id IN"
                        + " (SELECT dblink('x','y') FROM biz.other)",
                    ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  @Test
  void validate_pgSleepInOrderBy_throws() {
    // #769 C1 递归下钻 ORDER BY 标量表达式 —— 顶层 select item 干净但 ORDER BY 藏 DoS 调用。
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT id FROM biz.signal ORDER BY pg_sleep(5)", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  // ── S9: 子查询 / CTE 内的 SELECT * 也要拦 ──────────────────────────────────
  @Test
  void validate_selectStarInSubquery_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate("SELECT c FROM (SELECT * FROM biz.signal) t", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  void validate_selectStarInCte_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "WITH s AS (SELECT * FROM biz.signal) SELECT c FROM s", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  void validate_pgSleepFor_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT pg_sleep_for('10 seconds') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep_for");
  }

  @Test
  void validate_pgSleepUntil_throws() {
    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT pg_sleep_until(now() + '1h') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep_until");
  }

  @Test
  void validate_dblinkFamily_throws() {
    // 家族前缀匹配:dblink 一条覆盖 dblink_exec / dblink_connect / dblink_send_query。
    for (String fn :
        List.of(
            "dblink_exec('x','drop table biz.signal')",
            "dblink_connect('host=evil')",
            "dblink_send_query('c','select 1')")) {
      assertThatThrownBy(
              () -> SensorSqlValidator.validate("SELECT " + fn + " AS c FROM biz.signal", ALLOWED))
          .as("expected %s rejected", fn)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("forbidden function");
    }
  }

  @Test
  void validate_forbiddenFunctionsDisabled_allowsPgSleep() {
    // 传空黑名单时不做函数校验（保留调用方按需关闭的能力）。
    String sql = "SELECT pg_sleep(0) AS c FROM biz.signal";
    assertThat(SensorSqlValidator.validate(sql, ALLOWED, List.of())).isEqualTo(sql);
  }

  // ── W1-4: 配置源统一守护 ──────────────────────────────────────────────────
  // sensor/DQ 的默认禁用函数黑名单必须与 batch-common 单一权威源同一份数据，不得再各侧硬编码字面量各自维护。

  @Test
  void defaultForbiddenFunctions_isSameSourceAsBatchCommon() {
    assertThat(SensorSqlValidator.DEFAULT_FORBIDDEN_FUNCTIONS)
        .isSameAs(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
  }

  @Test
  void validate_blocksFunctionAddedToSharedSource() {
    // 模拟"单一源加一个禁用函数"：在共享源基础上追加一个仅测试用的函数名，sensor 侧必须同样拦住它——
    // 证明它是从共享清单派生的运行时列表，而非另一份独立硬编码副本。
    List<String> extended = new ArrayList<>(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
    extended.add("w1_4_test_only_marker_fn");

    assertThatThrownBy(
            () ->
                SensorSqlValidator.validate(
                    "SELECT w1_4_test_only_marker_fn(1) AS c FROM biz.signal", ALLOWED, extended))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("w1_4_test_only_marker_fn");
  }
}
