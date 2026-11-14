package io.github.pinpols.batch.sdk.handler.atomic;

import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import io.github.pinpols.batch.sdk.internal.ExceptionLogSummary;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SqlAtomicHandler} 切分多语句时采用的词法规则,按连接的 {@link DatabaseMetaData#getDatabaseProductName()} 选择。
 *
 * <p>SDK 的 {@link javax.sql.DataSource} 由租户传入,可能连 PostgreSQL 以外的库,所以需要区分方言:
 *
 * <ul>
 *   <li>产品名含 {@code mysql} 或 {@code mariadb}(MariaDB Connector/J 可能报告为 {@code MySQL}):按 MySQL 词法切分,并读取会话
 *       {@code @@SESSION.sql_mode} 判断 {@code NO_BACKSLASH_ESCAPES} 与 {@code ANSI_QUOTES}({@code ANSI} 组合模式在
 *       {@code @@sql_mode} 中会展开出 {@code ANSI_QUOTES});读取失败时按 MySQL 默认值(反斜杠转义开、{@code "} 为字符串)处理。
 *   <li>其余产品(含 PostgreSQL、读取元数据失败与未知方言):沿用 PostgreSQL 规则。Oracle、SQL Server 未单独实现:Oracle 的 PL/SQL
 *       块与 SQL Server 的批分隔符 {@code GO} 本就不能靠 {@code ;} 切分,{@code /} / {@code GO} 分隔、Oracle {@code q'[...]'}
 *       与 SQL Server {@code [...]} 标识符都不在支持范围。
 * </ul>
 *
 * @param mysql 是否按 MySQL / MariaDB 词法切分
 * @param backslashEscapes MySQL 下反斜杠是否为转义符(会话未开启 {@code NO_BACKSLASH_ESCAPES})
 * @param ansiQuotes MySQL 下 {@code "} 是否为标识符引号(会话开启了 {@code ANSI_QUOTES})
 */
@Slf4j
record SqlSplitDialect(boolean mysql, boolean backslashEscapes, boolean ansiQuotes) {

  /** PostgreSQL 规则;也是未知方言的默认值。 */
  static final SqlSplitDialect POSTGRESQL = new SqlSplitDialect(false, false, false);

  private static final String SQL_MODE_QUERY = "select @@SESSION.sql_mode";

  /** 按 MySQL 会话 {@code sql_mode} 文本构造 MySQL 规则;空值视为默认模式。 */
  static SqlSplitDialect mysql(String sqlMode) {
    String normalizedMode = EmptyChecks.isNull(sqlMode) ? "" : sqlMode.toUpperCase(Locale.ROOT);
    List<String> modes = List.of(normalizedMode.split(","));
    return new SqlSplitDialect(
        true, !modes.contains("NO_BACKSLASH_ESCAPES"), modes.contains("ANSI_QUOTES"));
  }

  /** 读取连接的产品名决定方言;MySQL 家族再读取一次会话 {@code sql_mode}。 */
  static SqlSplitDialect detect(Connection conn) {
    String productName;
    try {
      DatabaseMetaData meta = conn.getMetaData();
      productName = EmptyChecks.isNull(meta) ? null : meta.getDatabaseProductName();
    } catch (SQLException ex) {
      log.warn(
          "read database product name failed, splitting SQL with PostgreSQL rules: {}",
          ExceptionLogSummary.of(ex));
      return POSTGRESQL;
    }
    return isMySqlFamily(productName) ? mysql(readSqlMode(conn)) : POSTGRESQL;
  }

  /** 按本方言把 SQL 文本切成语句列表。 */
  List<String> split(String sql) {
    return mysql
        ? MySqlStatementSplitter.split(sql, backslashEscapes, ansiQuotes)
        : SqlAtomicHandler.splitStatements(sql);
  }

  private static boolean isMySqlFamily(String productName) {
    if (EmptyChecks.isNull(productName)) {
      return false;
    }
    String normalizedName = productName.toLowerCase(Locale.ROOT);
    return normalizedName.contains("mysql") || normalizedName.contains("mariadb");
  }

  /** 读取失败或无结果时返回空串,即按 MySQL 默认模式切分。 */
  private static String readSqlMode(Connection conn) {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(SQL_MODE_QUERY)) {
      return rs.next() ? rs.getString(1) : "";
    } catch (SQLException ex) {
      log.warn(
          "read MySQL sql_mode failed, assuming default backslash escapes: {}",
          ExceptionLogSummary.of(ex));
      return "";
    }
  }
}
