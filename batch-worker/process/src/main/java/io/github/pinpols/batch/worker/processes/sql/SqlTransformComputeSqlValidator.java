package io.github.pinpols.batch.worker.processes.sql;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator.SchemaViolation;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * SQL Transform 只允许配置 SELECT/WITH 源 SQL，并限制可访问 schema。
 *
 * <p>SELECT * / schema allowlist / 禁用函数三条规则的 AST 遍历逻辑委托 {@link SelectSqlAstValidator} （与 export 的
 * {@code SqlTemplateExportSqlValidator} 共享同一套树遍历，避免规则漂移）。
 */
public class SqlTransformComputeSqlValidator {

  /** 校验失败统一 i18n key,详情走 messageArgs[0]。 */
  private static final String ERR_KEY = "error.process.sql_validation";

  private final SqlTransformComputeSecurityProperties security;

  public SqlTransformComputeSqlValidator(SqlTransformComputeSecurityProperties security) {
    this.security = security;
  }

  /** 业务源 SQL 校验:AST + 禁 SELECT * + schema allowlist。 */
  public String validateSelect(String raw) {
    return validate(raw, true);
  }

  /**
   * 校验 VALIDATE 阶段的用户 check SQL:AST + 禁 SELECT *,且只能读取 {@link
   * SqlTransformComputePlugin#STAGING_TABLE}。
   */
  public String validateUserCheckSelect(String raw) {
    String sql = validate(raw, false);
    Statement statement = parse(sql);
    List<String> tableNames = List.copyOf(new TablesNamesFinder<Void>().getTables(statement));
    for (String tableName : tableNames) {
      if (!SqlTransformComputePlugin.STAGING_TABLE.equals(tableName.toLowerCase(Locale.ROOT))) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ERR_KEY,
            "sqlTransformCompute validation SQL may only read "
                + SqlTransformComputePlugin.STAGING_TABLE
                + ", found: "
                + tableName);
      }
    }
    return sql;
  }

  private String validate(String raw, boolean enforceSchemaAllowlist) {
    String sql = raw == null ? "" : raw.trim();
    if (!Texts.hasText(sql)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, ERR_KEY, "sqlTransformCompute SQL is blank");
    }

    Statement statement = parse(sql);
    if (!(statement instanceof Select select)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute only allows SELECT/WITH queries, got: "
              + statement.getClass().getSimpleName());
    }
    if (security == null || security.isForbidSelectStar()) {
      checkNoSelectStar(select);
    }
    if (enforceSchemaAllowlist
        && security != null
        && security.getAllowedSchemas() != null
        && !security.getAllowedSchemas().isEmpty()) {
      checkAllowedSchemas(statement, security.getAllowedSchemas());
    }
    if (security != null
        && security.getForbiddenFunctions() != null
        && !security.getForbiddenFunctions().isEmpty()) {
      checkNoForbiddenFunctions(statement, security.getForbiddenFunctions());
    }
    // requireLimit 仅对主源 SQL (业务路径) 强制;VALIDATE 阶段读 process_staging 的检查 SQL 表本身已小,豁免。
    if (enforceSchemaAllowlist && security != null && security.isRequireLimit()) {
      checkTopLevelLimit(select, security.getMaxLimitRows());
    }
    return sql;
  }

  /**
   * 遍历已解析 AST 的所有函数调用节点,命中禁用列表(dblink / pg_terminate_backend / pg_read_server_files 等)即拒。 具体遍历实现见
   * {@link SelectSqlAstValidator#findForbiddenFunctionCall}——AST 而非子串匹配:子串方案被注释绕过——{@code
   * pg_read_server_files/**}{@code /('x')}这类"函数名与左括号之间插注释"的写法,jsqlparser 正常解析为函数调用,但子串方案的"右侧紧跟
   * {@code (}"判定只跳空白不跳注释 → 漏判放行;带引号标识符 {@code "pg_..."(...)} 同样逃逸。AST 遍历直接看函数节点名,杜绝这两类绕过。
   */
  private static void checkNoForbiddenFunctions(Statement statement, List<String> forbidden) {
    String hit = SelectSqlAstValidator.findForbiddenFunctionCall(statement, forbidden);
    if (hit != null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute SQL calls forbidden function '" + hit + "'");
    }
  }

  /** 顶层 SELECT 必须带 LIMIT,且 ≤ maxLimitRows。SetOperationList / WITH 一并校验。 */
  private static void checkTopLevelLimit(Select select, long maxLimitRows) {
    Long limit = topLimitOf(select);
    if (limit == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute SQL must include a top-level LIMIT clause (≤ "
              + maxLimitRows
              + " rows)");
    }
    if (limit > maxLimitRows) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute SQL LIMIT " + limit + " exceeds max " + maxLimitRows);
    }
  }

  private static Long topLimitOf(Select body) {
    if (body instanceof PlainSelect ps && ps.getLimit() != null) {
      return numericLimit(ps.getLimit().getRowCount());
    }
    if (body instanceof SetOperationList sol && sol.getLimit() != null) {
      return numericLimit(sol.getLimit().getRowCount());
    }
    return null;
  }

  /**
   * 顶层 LIMIT 的行数必须是明确的数值字面量。返回 {@code null} 表示无 LIMIT 子句(由调用方判定是否强制)。
   *
   * <p>S8:此前对 {@code LIMIT :pageSize} / {@code LIMIT (SELECT ...)} / {@code LIMIT CASE ...} 这类非数值
   * rowCount 在 {@code NumberFormatException} 后返 0 视为通过,{@code maxLimitRows} 上限被完全绕过。改为直接拒绝——
   * requireLimit 的目的就是给结果集封顶,非数值 LIMIT 无法在解析期证明 ≤ 上限,一律拒。
   */
  private static Long numericLimit(Object rowCount) {
    if (rowCount == null) {
      return null;
    }
    try {
      return Long.parseLong(rowCount.toString().trim());
    } catch (NumberFormatException e) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute SQL top-level LIMIT must be a numeric literal (≤ maxLimitRows),"
              + " got non-numeric: "
              + rowCount);
    }
  }

  private Statement parse(String sql) {
    try {
      return CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          e,
          "sqlTransformCompute SQL parse error: " + e.getMessage());
    }
  }

  private void checkNoSelectStar(Select select) {
    if (SelectSqlAstValidator.containsSelectStar(select)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute forbids SELECT * / SELECT table.*; enumerate columns explicitly");
    }
  }

  private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
    SchemaViolation violation =
        SelectSqlAstValidator.findSchemaViolation(statement, allowedSchemas);
    if (violation == null) {
      return;
    }
    if (violation.unqualified()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          "sqlTransformCompute requires fully-qualified table names (schema.table), found: "
              + violation.violatingName());
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT,
        ERR_KEY,
        "sqlTransformCompute references disallowed schema '"
            + violation.violatingName()
            + "' - allowed: "
            + allowedSchemas);
  }
}
