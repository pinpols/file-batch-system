package com.example.batch.worker.processes.sql;

import com.example.batch.common.utils.Texts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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

/** SQL Transform 只允许配置 SELECT/WITH 源 SQL，并限制可访问 schema。 */
public class SqlTransformComputeSqlValidator {

  private final SqlTransformComputeSecurityProperties security;

  public SqlTransformComputeSqlValidator(SqlTransformComputeSecurityProperties security) {
    this.security = security;
  }

  /** 业务源 SQL 校验:AST + 禁 SELECT * + schema allowlist。 */
  public String validateSelect(String raw) {
    return validate(raw, true);
  }

  /** 校验 VALIDATE 阶段的用户 check SQL:AST + 禁 SELECT *,且只能读取 batch.process_staging。 */
  public String validateUserCheckSelect(String raw) {
    String sql = validate(raw, false);
    Statement statement = parse(sql);
    List<String> tableNames = new TablesNamesFinder().getTableList(statement);
    for (String tableName : tableNames) {
      if (!"batch.process_staging".equals(tableName.toLowerCase())) {
        throw new IllegalArgumentException(
            "sqlTransformCompute validation SQL may only read batch.process_staging, found: "
                + tableName);
      }
    }
    return sql;
  }

  private String validate(String raw, boolean enforceSchemaAllowlist) {
    String sql = raw == null ? "" : raw.trim();
    if (!Texts.hasText(sql)) {
      throw new IllegalArgumentException("sqlTransformCompute SQL is blank");
    }

    Statement statement = parse(sql);
    if (!(statement instanceof Select select)) {
      throw new IllegalArgumentException(
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
    return sql;
  }

  private Statement parse(String sql) {
    try {
      return CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "sqlTransformCompute SQL parse error: " + e.getMessage(), e);
    }
  }

  private void checkNoSelectStar(Select select) {
    Deque<SelectBody> queue = collectInitialBodies(select);
    while (!queue.isEmpty()) {
      SelectBody body = queue.poll();
      if (body instanceof PlainSelect ps) {
        rejectStarItems(ps);
        enqueueNestedBodies(ps, queue);
      } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
        queue.addAll(sol.getSelects());
      }
    }
  }

  /** 收集顶层 SelectBody + 所有 WITH 子句的子查询主体。 */
  private static Deque<SelectBody> collectInitialBodies(Select select) {
    Deque<SelectBody> queue = new ArrayDeque<>();
    if (select.getSelectBody() != null) {
      queue.add(select.getSelectBody());
    }
    if (select.getWithItemsList() != null) {
      for (WithItem wi : select.getWithItemsList()) {
        SubSelect sub = wi.getSubSelect();
        if (sub != null && sub.getSelectBody() != null) {
          queue.add(sub.getSelectBody());
        }
      }
    }
    return queue;
  }

  /** 单个 PlainSelect 命中 SELECT * / table.* 即抛。 */
  private static void rejectStarItems(PlainSelect ps) {
    if (ps.getSelectItems() == null) {
      return;
    }
    for (SelectItem item : ps.getSelectItems()) {
      if (item instanceof AllColumns || item instanceof AllTableColumns) {
        throw new IllegalArgumentException(
            "sqlTransformCompute forbids SELECT * / SELECT table.*;"
                + " enumerate columns explicitly");
      }
    }
  }

  /** 把 FROM / JOIN 中的子查询加入待检查队列。 */
  private static void enqueueNestedBodies(PlainSelect ps, Deque<SelectBody> queue) {
    if (ps.getFromItem() instanceof SubSelect sub && sub.getSelectBody() != null) {
      queue.add(sub.getSelectBody());
    }
    if (ps.getJoins() != null) {
      ps.getJoins().stream()
          .filter(j -> j.getRightItem() instanceof SubSelect)
          .map(j -> ((SubSelect) j.getRightItem()).getSelectBody())
          .filter(b -> b != null)
          .forEach(queue::add);
    }
  }

  private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
    List<String> tableNames = new TablesNamesFinder().getTableList(statement);
    for (String name : tableNames) {
      int dot = name.indexOf('.');
      if (dot <= 0) {
        throw new IllegalArgumentException(
            "sqlTransformCompute requires fully-qualified table names (schema.table), found: "
                + name);
      }
      String schema = name.substring(0, dot).toLowerCase();
      if (!allowedSchemas.contains(schema)) {
        throw new IllegalArgumentException(
            "sqlTransformCompute references disallowed schema '"
                + schema
                + "' - allowed: "
                + allowedSchemas);
      }
    }
  }
}
