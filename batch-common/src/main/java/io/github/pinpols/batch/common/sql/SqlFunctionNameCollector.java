package io.github.pinpols.batch.common.sql;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** 按 SQL AST 的完整遍历路径收集函数名，供安全校验使用。 */
final class SqlFunctionNameCollector {

  private SqlFunctionNameCollector() {}

  /**
   * 收集语句 AST 里所有函数调用节点的名字（小写、去引号）。以 {@link TablesNamesFinder} 的全树遍历为基座（它走遍 FROM/WHERE/select
   * item/函数参数 + 各类子查询），覆写 {@code visit(Function)} 作为副作用采集，{@code getTables} 触发 init+accept。
   *
   * <p>但 TablesNamesFinder（jsqlparser 5.3 实测）<b>不下钻</b> ORDER BY / GROUP BY / HAVING / LIMIT /
   * OFFSET 的标量表达式，也不把窗口函数 {@code fn(...) OVER (...)}（独立的 {@link AnalyticExpression} 节点，不走 {@code
   * visit(Function)}）算进来——{@code ORDER BY pg_terminate_backend(pid)} 这类调用会漏采直接放行。这里覆写 {@code
   * visit(PlainSelect)} / {@code visit(SetOperationList)} 显式补走上述子句（嵌套子查询/CTE 会经同一 visitor
   * 递归回到这两个覆写，故深层同类子句一并覆盖），并覆写 {@code visit(AnalyticExpression)} 采集窗口函数名 + 下钻 OVER(...) 内部表达式。
   */
  static Set<String> collect(Statement statement) {
    Set<String> names = new HashSet<>();
    TablesNamesFinder<Void> finder = new TablesNamesFinder<>() {
      @Override
      public <S> Void visit(Function function, S context) {
        if (EmptyChecks.isNotNull(function.getName())) {
          // 去引号：防 "pg_read_server_files"(...) 这类带引号标识符逃逸比对。
          String lowerCaseName = function.getName().toLowerCase(Locale.ROOT);
          String normalizedName = lowerCaseName.replace("\"", "");
          names.add(normalizedName);
        }
        return super.visit(function, context);
      }

      @Override
      public <S> Void visit(AnalyticExpression aexpr, S context) {
        // 窗口/分析函数是 AnalyticExpression 而非 Function 节点,名字单独采集,内部表达式手动下钻。
        if (EmptyChecks.isNotNull(aexpr.getName())) {
          String lowerCaseName = aexpr.getName().toLowerCase(Locale.ROOT);
          String normalizedName = lowerCaseName.replace("\"", "");
          names.add(normalizedName);
        }
        acceptIfPresent(aexpr.getExpression(), context);
        acceptIfPresent(aexpr.getOffset(), context);
        acceptIfPresent(aexpr.getDefaultValue(), context);
        if (EmptyChecks.isNotNull(aexpr.getPartitionExpressionList())) {
          aexpr.getPartitionExpressionList().accept(this, context);
        }
        acceptOrderBy(aexpr.getOrderByElements(), context);
        return super.visit(aexpr, context);
      }

      @Override
      public <S> Void visit(PlainSelect plainSelect, S context) {
        Void r = super.visit(plainSelect, context);
        acceptGroupingClauses(
            EmptyChecks.isNull(plainSelect.getGroupBy())
                ? null
                : plainSelect.getGroupBy().getGroupByExpressionList(),
            plainSelect.getHaving(),
            context);
        acceptOrderingClauses(
            plainSelect.getOrderByElements(),
            plainSelect.getLimit(),
            plainSelect.getOffset(),
            context);
        return r;
      }

      @Override
      public <S> Void visit(SetOperationList setOpList, S context) {
        Void r = super.visit(setOpList, context);
        acceptOrderingClauses(
            setOpList.getOrderByElements(), setOpList.getLimit(), setOpList.getOffset(), context);
        return r;
      }

      /** 补走 TablesNamesFinder 缺席的分组子句:GROUP BY / HAVING。 */
      private <S> void acceptGroupingClauses(Expression groupBy, Expression having, S context) {
        acceptIfPresent(groupBy, context);
        acceptIfPresent(having, context);
      }

      /** 补走 TablesNamesFinder 缺席的排序与分页子句:ORDER BY / LIMIT / OFFSET。 */
      private <S> void acceptOrderingClauses(
          List<OrderByElement> orderBy, Limit limit, Offset offset, S context) {
        acceptOrderBy(orderBy, context);
        if (EmptyChecks.isNotNull(limit)) {
          acceptIfPresent(limit.getRowCount(), context);
          acceptIfPresent(limit.getOffset(), context);
        }
        if (EmptyChecks.isNotNull(offset)) {
          acceptIfPresent(offset.getOffset(), context);
        }
      }

      private <S> void acceptOrderBy(List<OrderByElement> orderBy, S context) {
        if (EmptyChecks.isNotNull(orderBy)) {
          for (OrderByElement obe : orderBy) {
            acceptIfPresent(obe.getExpression(), context);
          }
        }
      }

      private <S> void acceptIfPresent(Expression e, S context) {
        if (EmptyChecks.isNotNull(e)) {
          e.accept(this, context);
        }
      }
    };
    finder.getTables(statement); // 触发全树遍历；副作用填充 names
    return names;
  }
}
