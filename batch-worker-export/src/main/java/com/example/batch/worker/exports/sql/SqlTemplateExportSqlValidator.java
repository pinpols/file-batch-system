package com.example.batch.worker.exports.sql;

import com.example.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
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
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import com.example.batch.common.utils.Texts;

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

    checkRequiredParams(sql);

    return sql;
  }

  /** 遍历语句中所有 PlainSelect 主体，拒绝 SELECT * 或 SELECT table.*。 */
  private void checkNoSelectStar(Select select) {
    Deque<SelectBody> queue = new ArrayDeque<>();
    // 收集主体及所有 WITH 子句主体
    if (select.getSelectBody() != null) {
      queue.add(select.getSelectBody());
    }
    if (select.getWithItemsList() != null) {
      for (WithItem wi : select.getWithItemsList()) {
        if (wi.getSubSelect() != null && wi.getSubSelect().getSelectBody() != null) {
          queue.add(wi.getSubSelect().getSelectBody());
        }
      }
    }

    while (!queue.isEmpty()) {
      SelectBody body = queue.poll();
      if (body instanceof PlainSelect ps) {
        if (ps.getSelectItems() != null) {
          for (SelectItem item : ps.getSelectItems()) {
            if (item instanceof AllColumns || item instanceof AllTableColumns) {
              throw new IllegalArgumentException(
                  "sql_template_export forbids SELECT * / SELECT table.*;"
                      + " enumerate columns explicitly");
            }
          }
        }
        // 将 FROM 中的子查询加入队列
        if (ps.getFromItem() instanceof SubSelect sub && sub.getSelectBody() != null) {
          queue.add(sub.getSelectBody());
        }
        // 将 JOIN 中的子查询加入队列
        if (ps.getJoins() != null) {
          ps.getJoins().stream()
              .filter(j -> j.getRightItem() instanceof SubSelect)
              .map(j -> ((SubSelect) j.getRightItem()).getSelectBody())
              .filter(b -> b != null)
              .forEach(queue::add);
        }
      } else if (body instanceof SetOperationList sol) {
        if (sol.getSelects() != null) {
          queue.addAll(sol.getSelects());
        }
      }
    }
  }

  /**
   * 通过 TablesNamesFinder 收集所有表名（可能含 "schema.table"），验证每个 schema 均在白名单内。
   *
   * <p>当 {@code allowedSchemas} 非空时，所有表名必须携带 schema 前缀（{@code schema.table}
   * 格式）；无前缀的表名一律拒绝，以防止绕过白名单访问系统内部表。
   */
  private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
    List<String> tableNames = new TablesNamesFinder().getTableList(statement);
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
    // 之前仅校验必填存在，未知参数留给 NamedParameterJdbcTemplate 运行时报错——把暴露前移到模板加载阶段。
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
   * 提取 SQL 中所有 {@code :paramName} 引用。
   * 简化处理：忽略字符串字面量 / 注释里的冒号；真需要全量覆盖可换用 JSqlParser 的 parameter visitor。
   * 当前实现足以覆盖常见 SQL 模板（冒号参数不会出现在字符串里）。
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
