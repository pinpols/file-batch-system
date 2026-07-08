package io.github.pinpols.batch.worker.core.sql;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Function;
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
 * export（{@code SqlTemplateExportSqlValidator}）与 process（{@code SqlTransformComputeSqlValidator}）
 * 共享的 SELECT/WITH AST 校验核心 —— SELECT * 检测、schema allowlist 检测、禁用函数调用检测三条规则的树遍历逻辑此前在两侧 逐行重复维护，导致
 * process 早先已经把禁用函数检测升级为 AST 遍历（防"函数名与左括号间插注释"及带引号标识符两类绕过），而 export
 * 仍停留在旧的大小写不敏感子串匹配实现——同一条注入路径只在一侧被拦，另一侧存在安全口子。这里统一成 AST 版本，两侧都受益。
 *
 * <p>本类只做规则判定、不抛业务异常——两个调用方的错误契约不同（export 用 {@code IllegalArgumentException}，process 用 {@code
 * BizException} + i18n key），由各自的 validator 按判定结果构造异常与文案。
 */
public final class SelectSqlAstValidator {

  private SelectSqlAstValidator() {}

  /** SELECT/WITH 主体（含所有 WITH 子查询、UNION 分支、嵌套子查询）中是否出现 {@code SELECT *} 或 {@code SELECT table.*}。 */
  public static boolean containsSelectStar(Select select) {
    Deque<Select> queue = collectInitialBodies(select);
    while (!queue.isEmpty()) {
      Select body = unwrap(queue.poll());
      if (body instanceof PlainSelect ps) {
        if (hasStarItem(ps)) {
          return true;
        }
        enqueueNestedBodies(ps, queue);
      } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
        queue.addAll(sol.getSelects());
      }
    }
    return false;
  }

  private static boolean hasStarItem(PlainSelect ps) {
    if (ps.getSelectItems() == null) {
      return false;
    }
    for (SelectItem<?> item : ps.getSelectItems()) {
      Object expression = item.getExpression();
      if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
        return true;
      }
    }
    return false;
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
   * Schema allowlist 违规结果。{@code unqualified=true} 表示表名未带 schema 前缀（{@code violatingName}
   * 即该表名）；{@code unqualified=false} 表示带前缀但不在白名单（{@code violatingName} 即该 schema）。
   */
  public record SchemaViolation(String violatingName, boolean unqualified) {}

  /**
   * 通过 TablesNamesFinder 收集所有表名（可能含 "schema.table"），校验每个都带 schema 前缀且在白名单内。
   * 无前缀的表名一律视为违规，防止绕过白名单访问系统内部表。全部合规返回 {@code null}。
   */
  public static SchemaViolation findSchemaViolation(
      Statement statement, List<String> allowedSchemas) {
    List<String> tableNames = List.copyOf(new TablesNamesFinder<Void>().getTables(statement));
    for (String name : tableNames) {
      int dot = name.indexOf('.');
      if (dot <= 0) {
        return new SchemaViolation(name, true);
      }
      String schema = name.substring(0, dot).toLowerCase(Locale.ROOT);
      if (!allowedSchemas.contains(schema)) {
        return new SchemaViolation(schema, false);
      }
    }
    return null;
  }

  /**
   * 遍历已解析 AST 的所有函数调用节点，命中禁用列表（dblink / pg_terminate_backend / pg_read_server_files 等）即返回该函数名， 否则返回
   * {@code null}。取代旧的子串匹配 —— 后者可被"函数名与左括号间插入块注释"（{@code pg_read_server_files/**}{@code
   * /('x')}）或带引号标识符（{@code "pg_read_server_files"(...)}）绕过。
   */
  public static String findForbiddenFunctionCall(Statement statement, List<String> forbidden) {
    Set<String> called = collectFunctionNames(statement);
    Set<String> forbiddenLower = new HashSet<>();
    for (String fn : forbidden) {
      forbiddenLower.add(fn.toLowerCase(Locale.ROOT));
    }
    for (String name : called) {
      // 既比对裸名，也比对 schema 限定名的尾段（pg_catalog.pg_read_server_files → pg_read_server_files）。
      String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
      if (forbiddenLower.contains(name) || forbiddenLower.contains(bare)) {
        return name;
      }
    }
    return null;
  }

  /**
   * 收集语句 AST 里所有函数调用节点的名字（小写、去引号）。复用 {@link TablesNamesFinder} 的全树遍历（它本就走遍 SELECT 各子句 + 子查询 +
   * 函数参数），覆写 {@code visit(Function)} 作为副作用采集，{@code getTables} 触发 init+accept。
   */
  private static Set<String> collectFunctionNames(Statement statement) {
    Set<String> names = new HashSet<>();
    TablesNamesFinder<Void> finder =
        new TablesNamesFinder<>() {
          @Override
          public <S> Void visit(Function function, S context) {
            if (function.getName() != null) {
              // 去引号：防 "pg_read_server_files"(...) 这类带引号标识符逃逸比对。
              names.add(function.getName().toLowerCase(Locale.ROOT).replace("\"", ""));
            }
            return super.visit(function, context);
          }
        };
    finder.getTables(statement); // 触发全树遍历；副作用填充 names
    return names;
  }
}
