package io.github.pinpols.batch.worker.exports.sql;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator.SchemaViolation;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * 使用 JSqlParser AST 分析验证模板提供的 SELECT SQL。
 *
 * <p>取代了此前基于字符串关键字扫描的实现（后者可通过 SQL 注释、大小写混用或空白绕过）。
 *
 * <p>检查项：
 *
 * <ol>
 *   <li>SQL 可解析且为 SELECT/WITH 语句（非 DML/DDL）
 *   <li>{@code forbidSelectStar=true} 时禁止 {@code SELECT *} / {@code SELECT table.*}
 *   <li>所有表 schema 引用须在 {@code allowedSchemas} 白名单内（非空时生效）
 *   <li>必填具名参数（默认 {@code :tenantId}、{@code :batchNo}）必须存在
 * </ol>
 *
 * <p>SELECT * / schema allowlist / 禁用函数三条规则的 AST 遍历逻辑委托 {@link SelectSqlAstValidator} （与 process 的
 * {@code SqlTransformComputeSqlValidator} 共享同一套树遍历，避免规则漂移）。
 */
public class SqlTemplateExportSqlValidator {

  private final SqlTemplateExportSecurityProperties security;

  public SqlTemplateExportSqlValidator(SqlTemplateExportSecurityProperties security) {
    this.security = security;
  }

  /** 验证并返回规范化（trim）后的 SQL；任一检查失败则抛出 {@link IllegalArgumentException}。 */
  public String validate(String raw) {
    String sql = raw == null ? "" : raw.trim();
    if (!Texts.hasText(sql)) {
      throw new IllegalArgumentException("default_query_sql is blank");
    }

    Statement statement;
    try {
      statement = CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "sql_template_export: SQL parse error — " + e.getMessage(), e);
    }

    if (!(statement instanceof Select select)) {
      throw new IllegalArgumentException(
          "sql_template_export only allows SELECT/WITH queries, got: "
              + statement.getClass().getSimpleName());
    }

    if (security != null && security.isForbidSelectStar()) {
      checkNoSelectStar(select);
    }

    if (security != null
        && security.getAllowedSchemas() != null
        && !security.getAllowedSchemas().isEmpty()) {
      checkAllowedSchemas(statement, security.getAllowedSchemas());
    }

    if (security != null
        && security.getForbiddenFunctions() != null
        && !security.getForbiddenFunctions().isEmpty()) {
      checkNoForbiddenFunctions(statement, security.getForbiddenFunctions());
    }

    checkRequiredParams(sql);

    return sql;
  }

  /** 遍历语句中所有 PlainSelect 主体，拒绝 SELECT * 或 SELECT table.*。 */
  private void checkNoSelectStar(Select select) {
    if (SelectSqlAstValidator.containsSelectStar(select)) {
      throw new IllegalArgumentException(
          "sql_template_export forbids SELECT * / SELECT table.*; enumerate columns explicitly");
    }
  }

  /**
   * 通过 TablesNamesFinder 收集所有表名（可能含 "schema.table"），验证每个 schema 均在白名单内。
   *
   * <p>当 {@code allowedSchemas} 非空时，所有表名必须携带 schema 前缀（{@code schema.table}
   * 格式）；无前缀的表名一律拒绝，以防止绕过白名单访问系统内部表。
   */
  private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
    SchemaViolation violation =
        SelectSqlAstValidator.findSchemaViolation(statement, allowedSchemas);
    if (violation == null) {
      return;
    }
    if (violation.unqualified()) {
      throw new IllegalArgumentException(
          "sql_template_export requires fully-qualified table names (schema.table),"
              + " but found unqualified name: '"
              + violation.violatingName()
              + "'");
    }
    throw new IllegalArgumentException("sql_template_export references disallowed schema '"
        + violation.violatingName()
        + "' — allowed: "
        + allowedSchemas);
  }

  /**
   * AST 遍历拒禁用函数(dblink / pg_terminate_backend 等)，与 process 侧 SqlTransformComputeSqlValidator 共用
   * {@link SelectSqlAstValidator#findForbiddenFunctionCall} 同一套规则实现，保持两条 SQL 路径一致守护 （此前 export
   * 是子串匹配，可被注释/带引号标识符绕过——见 SelectSqlAstValidator 类注释）。
   */
  private static void checkNoForbiddenFunctions(Statement statement, List<String> forbidden) {
    String hit = SelectSqlAstValidator.findForbiddenFunctionCall(statement, forbidden);
    if (hit != null) {
      throw new IllegalArgumentException(
          "sql_template_export SQL calls forbidden function '" + hit + "'");
    }
  }

  private void checkRequiredParams(String sql) {
    List<String> required =
        security == null ? List.of("tenantId", "batchNo") : security.getRequiredParams();
    Set<String> referenced = extractNamedParameters(sql);
    for (String param : required) {
      if (!Texts.hasText(param)) {
        continue;
      }
      if (!referenced.contains(param)) {
        throw new IllegalArgumentException(
            "sql_template_export must reference named parameter :" + param);
      }
    }
    // S-1.10：SQL 里出现的所有 :param 必须在白名单内（required + 引擎保留 + 允许扩展）。
    // 之前仅校验必填存在，未知参数留给 JDBC 运行时报错——把暴露前移到模板加载阶段。
    Set<String> allowed = new LinkedHashSet<>();
    allowed.addAll(required);
    // 引擎保留参数（Plugin 内部注入）
    allowed.add("__cursor");
    allowed.add("__limit");
    if (security != null && security.getAllowedExtraParams() != null) {
      allowed.addAll(security.getAllowedExtraParams());
    }
    for (String name : referenced) {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException(
            "sql_template_export references unknown named parameter :"
                + name
                + " — declare it in batch.worker.export.sql-template.allowed-extra-params or"
                + " remove the reference");
      }
    }
  }

  /**
   * 提取 SQL 中所有 {@code :paramName} 引用。
   *
   * <p>这里不能使用简单的正则：SQL 字符串、标识符、注释和 PostgreSQL dollar-quote 都允许出现冒号。若把这些内容
   * 误识别成参数，会错误放行缺失的租户参数；若把注释里的内容识别成未知参数，又会让合法模板在加载阶段失败。
   */
  static Set<String> extractNamedParameters(String sql) {
    Set<String> names = new LinkedHashSet<>();
    int index = 0;
    while (index < sql.length()) {
      int nextIndex = scanToken(sql, index, names);
      index = nextIndex > index ? nextIndex : index + 1;
    }
    return names;
  }

  private static int scanToken(String sql, int start, Set<String> names) {
    char current = sql.charAt(start);
    if (current == '\'') {
      return skipQuoted(sql, start, '\'', '\\');
    }
    if (current == '"') {
      return skipQuoted(sql, start, '"', '"');
    }
    if (current == '-' && hasNext(sql, start, '-')) {
      return skipLineComment(sql, start + 2);
    }
    if (current == '/' && hasNext(sql, start, '*')) {
      return skipBlockComment(sql, start + 2);
    }
    if (current == '$') {
      int dollarQuoteEnd = skipDollarQuote(sql, start);
      if (dollarQuoteEnd > start) {
        return dollarQuoteEnd;
      }
    }
    return captureNamedParameter(sql, start, names);
  }

  private static boolean hasNext(String sql, int index, char expected) {
    return index + 1 < sql.length() && sql.charAt(index + 1) == expected;
  }

  private static int captureNamedParameter(String sql, int start, Set<String> names) {
    if (sql.charAt(start) != ':'
        || start + 1 >= sql.length()
        || !isParameterStart(sql.charAt(start + 1))
        || (start > 0 && sql.charAt(start - 1) == ':')
        || sql.charAt(start + 1) == ':') {
      return start;
    }
    int end = start + 2;
    while (end < sql.length() && isParameterPart(sql.charAt(end))) {
      end++;
    }
    names.add(sql.substring(start + 1, end));
    return end;
  }

  private static boolean isParameterStart(char value) {
    return value == '_' || Character.isLetter(value);
  }

  private static boolean isParameterPart(char value) {
    return value == '_' || Character.isLetterOrDigit(value);
  }

  private static int skipQuoted(String sql, int start, char quote, char escape) {
    int index = start + 1;
    while (index < sql.length()) {
      char current = sql.charAt(index);
      if (current == quote) {
        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
          index += 2;
        } else {
          return index + 1;
        }
      } else if (quote == '\'' && current == escape && index + 1 < sql.length()) {
        index += 2;
      } else {
        index++;
      }
    }
    return sql.length();
  }

  private static int skipLineComment(String sql, int start) {
    int newline = sql.indexOf('\n', start);
    return newline < 0 ? sql.length() : newline + 1;
  }

  private static int skipBlockComment(String sql, int start) {
    int end = sql.indexOf("*/", start);
    return end < 0 ? sql.length() : end + 2;
  }

  /** 返回 dollar-quote 结束位置；当前字符不是 dollar-quote 时返回原位置。 */
  private static int skipDollarQuote(String sql, int start) {
    int delimiterEnd = dollarQuoteDelimiterEnd(sql, start);
    if (delimiterEnd == start) {
      return start;
    }
    String delimiter = sql.substring(start, delimiterEnd);
    int contentEnd = sql.indexOf(delimiter, delimiterEnd);
    return contentEnd < 0 ? sql.length() : contentEnd + delimiter.length();
  }

  private static int dollarQuoteDelimiterEnd(String sql, int start) {
    int index = start + 1;
    if (index >= sql.length()) {
      return start;
    }
    if (sql.charAt(index) == '$') {
      return index + 1;
    }
    if (!(sql.charAt(index) == '_' || Character.isLetter(sql.charAt(index)))) {
      return start;
    }
    index++;
    while (index < sql.length()
        && (sql.charAt(index) == '_' || Character.isLetterOrDigit(sql.charAt(index)))) {
      index++;
    }
    return index < sql.length() && sql.charAt(index) == '$' ? index + 1 : start;
  }
}
