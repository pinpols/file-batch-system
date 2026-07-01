package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * ADR-028 DB_ROW_EXISTS SQL 安全校验：
 *
 * <ul>
 *   <li>必须是 SELECT/WITH（拒绝 UPDATE / DELETE / DDL）
 *   <li>禁止 SELECT *（防止表 schema 变化时返回大字段）
 *   <li>所有引用表必须 {@code schema.table} 形式 + schema 必须在允许列表
 * </ul>
 *
 * <p>ADR-025 V16-e/f 静态校验复用本类。
 */
public final class SensorSqlValidator {

  private SensorSqlValidator() {}

  /**
   * 解析校验，返回 trim 后 SQL；失败抛 IllegalArgumentException 含原因。
   *
   * @param raw 用户填的 SQL
   * @param allowedSchemas lower-case 白名单
   */
  public static String validate(String raw, List<String> allowedSchemas) {
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
    if (allowedSchemas != null && !allowedSchemas.isEmpty()) {
      checkAllowedSchemas(statement, allowedSchemas);
    }
    return sql;
  }

  private static void checkNoSelectStar(Select select) {
    if (select instanceof PlainSelect ps) {
      rejectStar(ps);
    } else if (select instanceof SetOperationList sol && sol.getSelects() != null) {
      for (Select sb : sol.getSelects()) {
        if (sb instanceof PlainSelect ps) {
          rejectStar(ps);
        }
      }
    }
  }

  private static void rejectStar(PlainSelect ps) {
    if (ps.getSelectItems() == null) {
      return;
    }
    for (SelectItem<?> item : ps.getSelectItems()) {
      Object expression = item.getExpression();
      if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
        throw new IllegalArgumentException(
            "sensor SQL forbids SELECT * / table.*; enumerate columns explicitly");
      }
    }
  }

  private static void checkAllowedSchemas(Statement stmt, List<String> allowedSchemas) {
    List<String> tableNames = List.copyOf(new TablesNamesFinder<Void>().getTables(stmt));
    for (String name : tableNames) {
      int dot = name.indexOf('.');
      if (dot <= 0) {
        throw new IllegalArgumentException(
            "sensor SQL requires fully-qualified schema.table, found: " + name);
      }
      String schema = name.substring(0, dot).toLowerCase(Locale.ROOT);
      if (!allowedSchemas.contains(schema)) {
        throw new IllegalArgumentException(
            "sensor SQL references disallowed schema '"
                + schema
                + "' - allowed: "
                + allowedSchemas);
      }
    }
  }
}
