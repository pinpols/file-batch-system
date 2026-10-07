package io.github.pinpols.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("传感查询语句校验器: 语句形态, 模式白名单与禁用函数拦截口径")
class SensorSqlValidatorTest {

  private static final List<String> ALLOWED = List.of("biz");

  @Test
  @DisplayName("合法的模式限定查询语句通过校验并原样返回")
  void validate_simpleSelect_passes() {
    String sql = "SELECT id FROM biz.signal WHERE status = :status LIMIT 1";
    assertThat(SensorSqlValidator.validate(sql, ALLOWED)).isEqualTo(sql);
  }

  @Test
  @DisplayName("语句为空白时抛出参数非法异常并提示空白")
  void validate_blankSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("  ", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  @DisplayName("非查询语句被拒绝并提示只允许查询或公共表表达式")
  void shouldThrow_whenStatementIsNotSelect() {
    assertThatThrownBy(
            () -> SensorSqlValidator.validate("UPDATE biz.signal SET status='X'", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT/WITH");
  }

  @Test
  @DisplayName("使用通配列的查询被拒绝并提示禁止通配")
  void validate_selectStar_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT * FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  @DisplayName("访问未授权模式时被拒绝并提示模式不允许")
  void validate_disallowedSchema_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM hr.payroll", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disallowed schema");
  }

  @Test
  @DisplayName("未限定模式的表名被拒绝并提示需写成模式加点表名")
  void validate_unqualifiedTable_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema.table");
  }

  @Test
  @DisplayName("无法解析的语句被拒绝并提示解析错误")
  void validate_garbageSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("this is not sql", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parse error");
  }

  // ── S3: 禁用函数黑名单 ────────────────────────────────────────────────────
  @Test
  @DisplayName("调用暂停函数时被拒绝并指出禁用函数名")
  void validate_pgSleep_throws() {
    assertThatThrownBy(
            () -> SensorSqlValidator.validate("SELECT pg_sleep(10) FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep");
  }

  @Test
  @DisplayName("调用读取服务器文件函数时被拒绝并指出禁用函数名")
  void validate_pgReadFile_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "SELECT pg_read_file('/etc/passwd') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_read_file");
  }

  @Test
  @DisplayName("子查询中调用外部连接函数时同样被拒绝")
  void validate_dblinkInSubquery_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "SELECT id FROM biz.signal WHERE id IN" + " (SELECT dblink('x','y') FROM biz.other)",
            ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  @Test
  @DisplayName("排序表达式中隐藏暂停函数调用时同样被拒绝")
  void validate_pgSleepInOrderBy_throws() {
    // #769 C1 递归下钻 ORDER BY 标量表达式 —— 顶层 select item 干净但 ORDER BY 藏 DoS 调用。
    assertThatThrownBy(() ->
            SensorSqlValidator.validate("SELECT id FROM biz.signal ORDER BY pg_sleep(5)", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  // ── S9: 子查询 / CTE 内的 SELECT * 也要拦 ──────────────────────────────────
  @Test
  @DisplayName("子查询中使用通配列时同样被拒绝")
  void validate_selectStarInSubquery_throws() {
    assertThatThrownBy(() ->
            SensorSqlValidator.validate("SELECT c FROM (SELECT * FROM biz.signal) t", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  @DisplayName("公共表表达式中使用通配列时同样被拒绝")
  void validate_selectStarInCte_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "WITH s AS (SELECT * FROM biz.signal) SELECT c FROM s", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  @DisplayName("调用定时暂停函数时被拒绝并指出禁用函数名")
  void validate_pgSleepFor_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "SELECT pg_sleep_for('10 seconds') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep_for");
  }

  @Test
  @DisplayName("调用定点暂停函数时被拒绝并指出禁用函数名")
  void validate_pgSleepUntil_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "SELECT pg_sleep_until(now() + '1h') AS c FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("pg_sleep_until");
  }

  @Test
  @DisplayName("外部连接函数家族按前缀整体拦截")
  void validate_dblinkFamily_throws() {
    // 家族前缀匹配:dblink 一条覆盖 dblink_exec / dblink_connect / dblink_send_query。
    for (String fn : List.of(
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
  @DisplayName("禁用函数清单为空时跳过函数校验, 语句原样通过")
  void validate_forbiddenFunctionsDisabled_allowsPgSleep() {
    // 传空黑名单时不做函数校验（保留调用方按需关闭的能力）。
    String sql = "SELECT pg_sleep(0) AS c FROM biz.signal";
    assertThat(SensorSqlValidator.validate(sql, ALLOWED, List.of())).isEqualTo(sql);
  }

  // ── W1-4: 配置源统一守护 ──────────────────────────────────────────────────
  // sensor/DQ 的默认禁用函数黑名单必须与 batch-common 单一权威源同一份数据，不得再各侧硬编码字面量各自维护。

  @Test
  @DisplayName("默认禁用函数清单与公共校验组件共用同一份来源")
  void defaultForbiddenFunctions_isSameSourceAsBatchCommon() {
    assertThat(SensorSqlValidator.DEFAULT_FORBIDDEN_FUNCTIONS)
        .isSameAs(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
  }

  @Test
  @DisplayName("共享清单新增禁用函数后传感侧同样拦截, 证明清单为运行时派生")
  void validate_blocksFunctionAddedToSharedSource() {
    // 模拟"单一源加一个禁用函数"：在共享源基础上追加一个仅测试用的函数名，sensor 侧必须同样拦住它——
    // 证明它是从共享清单派生的运行时列表，而非另一份独立硬编码副本。
    List<String> extended = new ArrayList<>(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
    extended.add("w1_4_test_only_marker_fn");

    assertThatThrownBy(() -> SensorSqlValidator.validate(
            "SELECT w1_4_test_only_marker_fn(1) AS c FROM biz.signal", ALLOWED, extended))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function")
        .hasMessageContaining("w1_4_test_only_marker_fn");
  }
}
