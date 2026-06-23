package io.github.pinpols.batch.worker.exports.sql;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

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

    if (!(statement instanceof Select)) {
      throw new IllegalArgumentException(
          "sql_template_export only allows SELECT/WITH queries, got: "
              + statement.getClass().getSimpleName());
    }

    Select select = (Select) statement;

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
      checkNoForbiddenFunctions(sql, security.getForbiddenFunctions());
    }

    checkRequiredParams(sql);

    return sql;
  }

  /**
   * 子串匹配大小写不敏感地拒禁用函数(dblink / pg_terminate_backend 等),边界检查避免误判同名列。 与
   * SqlTransformComputeSqlValidator 同语义,保持 Export 与 Process 两条 SQL 路径一致守护。
   */
  private static void checkNoForbiddenFunctions(String sql, List<String> forbidden) {
    String lower = sql.toLowerCase();
    for (String fn : forbidden) {
      String needle = fn.toLowerCase();
      int idx = 0;
      while ((idx = lower.indexOf(needle, idx)) >= 0) {
        int after = idx + needle.length();
        boolean leftBoundary =
            idx == 0
                || !(Character.isLetterOrDigit(lower.charAt(idx - 1))
                    || lower.charAt(idx - 1) == '_');
        int p = after;
        while (p < lower.length() && Character.isWhitespace(lower.charAt(p))) p++;
        boolean rightCallSite = p < lower.length() && lower.charAt(p) == '(';
        if (leftBoundary && rightCallSite) {
          throw new IllegalArgumentException(
              "sql_template_export SQL calls forbidden function '" + fn + "'");
        }
        idx = after;
      }
    }
  }

  /** 遍历语句中所有 PlainSelect 主体，拒绝 SELECT * 或 SELECT table.*。 */
  private void checkNoSelectStar(Select select) {
    Deque<Select> queue = collectInitialBodies(select);
    while (!queue.isEmpty()) {
      Select body = unwrap(queue.poll());
      if (body instanceof PlainSelect ps) {
        rejectStarItems(ps);
        enqueueNestedBodies(ps, queue);
      } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
        for (Select s : sol.getSelects()) {
          queue.add(s);
        }
      }
    }
  }

  /** 解包 ParenthesedSelect → 真实的 PlainSelect / SetOperationList。 */
  private static Select unwrap(Select select) {
    while (select instanceof ParenthesedSelect ps) {
      select = ps.getSelect();
    }
    return select;
  }

  /** 收集顶层 Select + 所有 WITH 子句的子查询主体。 */
  private static Deque<Select> collectInitialBodies(Select select) {
    Deque<Select> queue = new ArrayDeque<>();
    queue.add(select);
    if (select.getWithItemsList() != null) {
      for (WithItem<?> wi : select.getWithItemsList()) {
        ParenthesedSelect sub = wi.getSelect();
        if (sub != null) {
          queue.add(sub);
        }
      }
    }
    return queue;
  }

  /** 单个 PlainSelect 的 selectItems 命中 SELECT * / table.* 即抛。 */
  private static void rejectStarItems(PlainSelect ps) {
    if (ps.getSelectItems() == null) {
      return;
    }
    for (SelectItem<?> item : ps.getSelectItems()) {
      Object expression = item.getExpression();
      if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
        throw new IllegalArgumentException(
            "sql_template_export forbids SELECT * / SELECT table.*;"
                + " enumerate columns explicitly");
      }
    }
  }

  /** 把 FROM / JOIN 中的子查询加入待检查队列。 */
  private static void enqueueNestedBodies(PlainSelect ps, Deque<Select> queue) {
    if (ps.getFromItem() instanceof Select sub) {
      queue.add(sub);
    }
    if (ps.getJoins() != null) {
      ps.getJoins().stream()
          .filter(j -> j.getRightItem() instanceof Select)
          .map(j -> (Select) j.getRightItem())
          .forEach(queue::add);
    }
  }

  /**
   * 通过 TablesNamesFinder 收集所有表名（可能含 "schema.table"），验证每个 schema 均在白名单内。
   *
   * <p>当 {@code allowedSchemas} 非空时，所有表名必须携带 schema 前缀（{@code schema.table}
   * 格式）；无前缀的表名一律拒绝，以防止绕过白名单访问系统内部表。
   */
  private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
    List<String> tableNames = List.copyOf(new TablesNamesFinder<Void>().getTables(statement));
    for (String name : tableNames) {
      int dot = name.indexOf('.');
      if (dot <= 0) {
        throw new IllegalArgumentException(
            "sql_template_export requires fully-qualified table names (schema.table),"
                + " but found unqualified name: '"
                + name
                + "'");
      }
      String schema = name.substring(0, dot).toLowerCase();
      if (!allowedSchemas.contains(schema)) {
        throw new IllegalArgumentException(
            "sql_template_export references disallowed schema '"
                + schema
                + "' — allowed: "
                + allowedSchemas);
      }
    }
  }

  private void checkRequiredParams(String sql) {
    List<String> required =
        security == null ? List.of("tenantId", "batchNo") : security.getRequiredParams();
    for (String param : required) {
      if (!Texts.hasText(param)) {
        continue;
      }
      if (!sql.contains(":" + param)) {
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
    Set<String> referenced = extractNamedParameters(sql);
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
   * 提取 SQL 中所有 {@code :paramName} 引用。 简化处理：忽略字符串字面量 / 注释里的冒号；真需要全量覆盖可换用 JSqlParser 的 parameter
   * visitor。 当前实现足以覆盖常见 SQL 模板（冒号参数不会出现在字符串里）。
   */
  private Set<String> extractNamedParameters(String sql) {
    Set<String> names = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)").matcher(sql);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }
}
