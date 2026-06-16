package com.example.batch.worker.processes.sql;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Function;
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
    List<String> tableNames = new TablesNamesFinder().getTableList(statement);
    for (String tableName : tableNames) {
      if (!SqlTransformComputePlugin.STAGING_TABLE.equals(tableName.toLowerCase())) {
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
   * 遍历已解析 AST 的所有函数调用节点,命中禁用列表(dblink / pg_terminate_backend / pg_read_server_files 等)即拒。
   *
   * <p>改用 AST 而非子串匹配:子串方案被注释绕过——{@code pg_read_server_files/**}{@code /('x')} 这类
   * "函数名与左括号之间插注释"的写法,jsqlparser 正常解析为函数调用,但子串的"右侧紧跟 {@code (}"判定只跳空白不跳注释 → 漏判放行;带引号标识符 {@code
   * "pg_..."(...)} 同样逃逸。AST 遍历直接看函数节点名,杜绝这两类绕过。
   */
  private static void checkNoForbiddenFunctions(Statement statement, List<String> forbidden) {
    Set<String> called = collectFunctionNames(statement);
    Set<String> forbiddenLower = new HashSet<>();
    for (String fn : forbidden) {
      forbiddenLower.add(fn.toLowerCase(Locale.ROOT));
    }
    for (String name : called) {
      // 既比对裸名,也比对 schema 限定名的尾段(pg_catalog.pg_read_server_files → pg_read_server_files)。
      String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
      if (forbiddenLower.contains(name) || forbiddenLower.contains(bare)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ERR_KEY,
            "sqlTransformCompute SQL calls forbidden function '" + name + "'");
      }
    }
  }

  /**
   * 收集语句 AST 里所有函数调用节点的名字(小写)。复用 {@link TablesNamesFinder} 的全树遍历(它本就走遍 SELECT 各子句 + 子查询 + 函数参数),覆写
   * {@code visit(Function)} 作为副作用采集,{@code getTables} 触发 init+accept。
   */
  private static Set<String> collectFunctionNames(Statement statement) {
    Set<String> names = new HashSet<>();
    TablesNamesFinder<Void> finder =
        new TablesNamesFinder<>() {
          @Override
          public <S> Void visit(Function function, S context) {
            if (function.getName() != null) {
              // 去引号:防 "pg_read_server_files"(...) 这类带引号标识符逃逸比对。
              names.add(function.getName().toLowerCase(Locale.ROOT).replace("\"", ""));
            }
            return super.visit(function, context);
          }
        };
    finder.getTables(statement); // 触发全树遍历;副作用填充 names
    return names;
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
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ERR_KEY,
          e,
          "sqlTransformCompute SQL parse error: " + e.getMessage());
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
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ERR_KEY,
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
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ERR_KEY,
            "sqlTransformCompute requires fully-qualified table names (schema.table), found: "
                + name);
      }
      String schema = name.substring(0, dot).toLowerCase();
      if (!allowedSchemas.contains(schema)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ERR_KEY,
            "sqlTransformCompute references disallowed schema '"
                + schema
                + "' - allowed: "
                + allowedSchemas);
      }
    }
  }
}
