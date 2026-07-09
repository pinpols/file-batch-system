package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator.SchemaViolation;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * ADR-028 DB_ROW_EXISTS SQL 安全校验：
 *
 * <ul>
 *   <li>必须是 SELECT/WITH（拒绝 UPDATE / DELETE / DDL）
 *   <li>禁止 SELECT *（防止表 schema 变化时返回大字段；含子查询 / CTE 内的 {@code *}）
 *   <li>所有引用表必须 {@code schema.table} 形式 + schema 必须在允许列表
 *   <li>禁止调用危险 PG 函数（文件读 {@code pg_read_file} / 连接 {@code dblink} / DoS {@code pg_sleep} 等）
 * </ul>
 *
 * <p>SELECT * / schema allowlist / 禁用函数三条规则的 AST 遍历逻辑委托 {@link SelectSqlAstValidator}（与 export /
 * process 共享同一套树遍历，避免规则漂移；子查询 / CTE / ORDER BY / 窗口函数等均下钻覆盖）。
 *
 * <p>ADR-025 V16-e/f 静态校验复用本类；ADR-021 DataQuality gate 亦复用（见 {@code DataQualityCheckExecutor}）。
 */
public final class SensorSqlValidator {

  private SensorSqlValidator() {}

  /**
   * sensor / DataQuality SQL 默认禁用的 PG 函数黑名单。覆盖：任意命令 / 网络连接（{@code dblink} / {@code
   * copy_from_program}）、后端控制（{@code pg_terminate_backend} / {@code
   * pg_cancel_backend}）、服务器文件读取（{@code pg_read_file} / {@code pg_read_binary_file} / {@code
   * pg_ls_dir} / {@code lo_import} / {@code lo_export}）、拒绝服务（{@code pg_sleep}）。与 worker
   * export/process 侧保持一致的安全边界。
   */
  public static final List<String> DEFAULT_FORBIDDEN_FUNCTIONS =
      List.of(
          "dblink",
          "pg_terminate_backend",
          "pg_cancel_backend",
          "pg_read_file",
          "pg_read_binary_file",
          "pg_read_server_files",
          "pg_ls_dir",
          "copy_from_program",
          "lo_import",
          "lo_export",
          "pg_sleep");

  /**
   * 解析校验，返回 trim 后 SQL；失败抛 IllegalArgumentException 含原因。使用 {@link #DEFAULT_FORBIDDEN_FUNCTIONS}
   * 黑名单。
   *
   * @param raw 用户填的 SQL
   * @param allowedSchemas lower-case 白名单
   */
  public static String validate(String raw, List<String> allowedSchemas) {
    return validate(raw, allowedSchemas, DEFAULT_FORBIDDEN_FUNCTIONS);
  }

  /**
   * 解析校验，返回 trim 后 SQL；失败抛 IllegalArgumentException 含原因。
   *
   * @param raw 用户填的 SQL
   * @param allowedSchemas lower-case 白名单
   * @param forbiddenFunctions 禁用函数黑名单（大小写不敏感，AST 遍历比对函数节点名）
   */
  public static String validate(
      String raw, List<String> allowedSchemas, List<String> forbiddenFunctions) {
    if (!Texts.hasText(raw)) {
      throw new IllegalArgumentException("sensor SQL is blank");
    }
    String sql = raw.trim();
    Statement statement;
    try {
      statement = CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw new IllegalArgumentException("sensor SQL parse error: " + e.getMessage(), e);
    }
    if (!(statement instanceof Select select)) {
      throw new IllegalArgumentException(
          "sensor SQL only allows SELECT/WITH, got: " + statement.getClass().getSimpleName());
    }
    checkNoSelectStar(select);
    if (forbiddenFunctions != null && !forbiddenFunctions.isEmpty()) {
      checkNoForbiddenFunctions(statement, forbiddenFunctions);
    }
    if (allowedSchemas != null && !allowedSchemas.isEmpty()) {
      checkAllowedSchemas(statement, allowedSchemas);
    }
    return sql;
  }

  private static void checkNoSelectStar(Select select) {
    if (SelectSqlAstValidator.containsSelectStar(select)) {
      throw new IllegalArgumentException(
          "sensor SQL forbids SELECT * / table.*; enumerate columns explicitly");
    }
  }

  private static void checkNoForbiddenFunctions(Statement stmt, List<String> forbidden) {
    String hit = SelectSqlAstValidator.findForbiddenFunctionCall(stmt, forbidden);
    if (hit != null) {
      throw new IllegalArgumentException("sensor SQL calls forbidden function '" + hit + "'");
    }
  }

  private static void checkAllowedSchemas(Statement stmt, List<String> allowedSchemas) {
    SchemaViolation violation = SelectSqlAstValidator.findSchemaViolation(stmt, allowedSchemas);
    if (violation == null) {
      return;
    }
    if (violation.unqualified()) {
      throw new IllegalArgumentException(
          "sensor SQL requires fully-qualified schema.table, found: " + violation.violatingName());
    }
    throw new IllegalArgumentException(
        "sensor SQL references disallowed schema '"
            + violation.violatingName()
            + "' - allowed: "
            + allowedSchemas);
  }
}
