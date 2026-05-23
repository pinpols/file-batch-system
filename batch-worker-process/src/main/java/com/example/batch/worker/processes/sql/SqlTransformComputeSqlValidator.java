package com.example.batch.worker.processes.sql;

import com.example.batch.common.utils.Texts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
    if (security != null
        && security.getForbiddenFunctions() != null
        && !security.getForbiddenFunctions().isEmpty()) {
      checkNoForbiddenFunctions(sql, security.getForbiddenFunctions());
    }
    // requireLimit 仅对主源 SQL (业务路径) 强制;VALIDATE 阶段读 process_staging 的检查 SQL 表本身已小,豁免。
    if (enforceSchemaAllowlist && security != null && security.isRequireLimit()) {
      checkTopLevelLimit(select, security.getMaxLimitRows());
    }
    return sql;
  }

  /**
   * 子串匹配大小写不敏感地拒禁用函数(dblink / pg_terminate_backend 等)。比 AST 遍历简单且能覆盖大小写/空格变体。 用 `\bname\b` 边界匹配避免
   * "dblink" 误命中 "dblink_connect_u" 列名 — 实际上禁用列表都是带括号 调用的函数名,在 SQL 中后跟 '(',我们以此为强信号。
   */
  private static void checkNoForbiddenFunctions(String sql, List<String> forbidden) {
    String lower = sql.toLowerCase();
    for (String fn : forbidden) {
      String needle = fn.toLowerCase();
      int idx = 0;
      while ((idx = lower.indexOf(needle, idx)) >= 0) {
        int after = idx + needle.length();
        boolean leftBoundary = idx == 0 || !isIdentifierPart(lower.charAt(idx - 1));
        boolean rightCallSite = after < lower.length() && nextIsLParen(lower, after);
        if (leftBoundary && rightCallSite) {
          throw new IllegalArgumentException(
              "sqlTransformCompute SQL calls forbidden function '" + fn + "'");
        }
        idx = after;
      }
    }
  }

  private static boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static boolean nextIsLParen(String s, int from) {
    int i = from;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i < s.length() && s.charAt(i) == '(';
  }

  /** 顶层 SELECT 必须带 LIMIT,且 ≤ maxLimitRows。SetOperationList / WITH 一并校验。 */
  private static void checkTopLevelLimit(Select select, long maxLimitRows) {
    Long limit = topLimitOf(select);
    if (limit == null) {
      throw new IllegalArgumentException(
          "sqlTransformCompute SQL must include a top-level LIMIT clause (≤ "
              + maxLimitRows
              + " rows)");
    }
    if (limit > maxLimitRows) {
      throw new IllegalArgumentException(
          "sqlTransformCompute SQL LIMIT " + limit + " exceeds max " + maxLimitRows);
    }
  }

  private static Long topLimitOf(Select body) {
    if (body instanceof PlainSelect ps && ps.getLimit() != null) {
      Object rows = ps.getLimit().getRowCount();
      if (rows == null) return null;
      try {
        return Long.parseLong(rows.toString().trim());
      } catch (NumberFormatException e) {
        // 动态 LIMIT 参数 (e.g., LIMIT :pageSize) — 视为通过,上限由调用方参数绑定保障
        return 0L;
      }
    }
    if (body instanceof SetOperationList sol && sol.getLimit() != null) {
      Object rows = sol.getLimit().getRowCount();
      if (rows == null) return null;
      try {
        return Long.parseLong(rows.toString().trim());
      } catch (NumberFormatException e) {
        return 0L;
      }
    }
    return null;
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

  /** 单个 PlainSelect 命中 SELECT * / table.* 即抛。 */
  private static void rejectStarItems(PlainSelect ps) {
    if (ps.getSelectItems() == null) {
      return;
    }
    for (SelectItem<?> item : ps.getSelectItems()) {
      Object expression = item.getExpression();
      if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
        throw new IllegalArgumentException(
            "sqlTransformCompute forbids SELECT * / SELECT table.*;"
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
