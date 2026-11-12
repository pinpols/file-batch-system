package io.github.pinpols.batch.sdk.handler.atomic;

import io.github.pinpols.batch.sdk.handler.SdkAbstractAtomicHandler;
import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import io.github.pinpols.batch.sdk.internal.ExceptionLogSummary;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的 SQL 原子执行 handler(ADR-036 Atomic 模板的 sql shape)。
 *
 * <p>从 {@code ctx.parameters().get("sql")} 取单条 SQL 文本,经 JDK {@code java.sql.*} 在 {@link DataSource}
 * 上执行;只用 JDK,无第三方连接池 / Spring 依赖。SELECT 返回 {@code resultSet/rowCount/truncated};DML 返回 {@code
 * affectedRows}。
 *
 * <p><b>安全闸</b>:默认 {@link SqlAtomicConfig#forbidOsCapableRole()} 为 true,执行前检测当前 DB 角色是否具备 superuser
 * 或 {@code pg_execute_server_program} / {@code pg_read_server_files} / {@code
 * pg_write_server_files} 等 OS 能力,命中则拒绝执行(dual-use RCE 隔离)。
 */
@Slf4j
public class SqlAtomicHandler extends SdkAbstractAtomicHandler<Map<String, Object>> {

  private static final String SQL_PARAM = "sql";

  private static final String OS_CAPABLE_ROLE_PROBE = "select rolsuper"
      + " or pg_has_role(current_user,'pg_execute_server_program','USAGE')"
      + " or pg_has_role(current_user,'pg_read_server_files','USAGE')"
      + " or pg_has_role(current_user,'pg_write_server_files','USAGE')"
      + " from pg_roles where rolname=current_user";

  private final SqlAtomicConfig config;
  private final DataSource dataSource;

  public SqlAtomicHandler(SqlAtomicConfig config, DataSource dataSource) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected Map<String, Object> doInvoke(SdkTaskContext ctx) throws Exception {
    String sql = readSql(ctx);
    List<String> statements = splitStatements(sql);
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("no executable SQL statement found");
    }
    if (statements.size() > config.maxStatementsPerJob()) {
      throw new IllegalArgumentException("too many statements: "
          + statements.size()
          + " > maxStatementsPerJob="
          + config.maxStatementsPerJob());
    }
    try (Connection conn = dataSource.getConnection()) {
      if (config.forbidOsCapableRole()) {
        assertNotOsCapableRole(conn);
      }
      return runStatements(conn, statements);
    }
  }

  /** 按 defaultAutoCommit 决定事务边界,逐条执行,聚合 affectedRows + 最后一个 ResultSet。 */
  private Map<String, Object> runStatements(Connection conn, List<String> statements)
      throws Exception {
    boolean autoCommit = config.defaultAutoCommit();
    boolean originalAutoCommit = conn.getAutoCommit();
    StatementTotals totals = new StatementTotals();
    try {
      conn.setAutoCommit(autoCommit);
      for (String one : statements) {
        runStatement(conn, one, totals);
      }
      if (!autoCommit) conn.commit();
    } catch (Exception ex) {
      if (!autoCommit) {
        rollbackQuietly(conn);
      }
      throw ex;
    } finally {
      try {
        conn.setAutoCommit(originalAutoCommit);
      } catch (Exception restoreEx) {
        log.warn("restore autoCommit failed: {}", ExceptionLogSummary.of(restoreEx));
      }
    }
    if (EmptyChecks.isNotNull(totals.lastResultSet)) {
      totals.lastResultSet.put("statementCount", statements.size());
      totals.lastResultSet.put("totalAffectedRows", totals.totalAffected);
      return totals.lastResultSet;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("statementCount", statements.size());
    out.put("affectedRows", totals.totalAffected);
    return out;
  }

  /** 逐条执行的聚合结果:受影响行数累计 + 最后一个 ResultSet。 */
  private static final class StatementTotals {
    long totalAffected;
    Map<String, Object> lastResultSet;
  }

  private void runStatement(Connection conn, String sql, StatementTotals totals) throws Exception {
    try (Statement st = conn.createStatement()) {
      st.setQueryTimeout(config.statementTimeoutSeconds());
      boolean hasRs = st.execute(sql);
      if (!hasRs) {
        int affected = st.getUpdateCount();
        if (affected > 0) totals.totalAffected += affected;
        return;
      }
      try (ResultSet rs = st.getResultSet()) {
        totals.lastResultSet = readResultSet(rs);
      }
    }
  }

  private void rollbackQuietly(Connection conn) {
    try {
      conn.rollback();
    } catch (Exception rbEx) {
      log.warn("rollback failed: {}", ExceptionLogSummary.of(rbEx));
    }
  }

  /** 按 `;` 切多语句,跳过单/双引号内、行/块注释内的 `;`。简化版,不处理 dollar-quote。 */
  static List<String> splitStatements(String sql) {
    StatementSplitState state = new StatementSplitState();
    for (int i = 0; i < sql.length(); i++) {
      state.append(sql.charAt(i));
    }
    return state.complete();
  }

  /** 引号、注释与前一个字符属于同一词法状态，不能在语句边界单独重置其中一项。 */
  private static final class StatementSplitState {
    private final List<String> statements = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    /** 词法状态:同一时刻只处于其一,与原四个互斥布尔标志一一对应。 */
    private enum LexState {
      CODE,
      LINE_COMMENT,
      BLOCK_COMMENT,
      SINGLE_QUOTE,
      DOUBLE_QUOTE
    }

    private LexState state = LexState.CODE;
    private char previous;

    private void append(char current) {
      switch (state) {
        case LINE_COMMENT -> {
          if (current == '\n') state = LexState.CODE;
          buffer.append(current);
        }
        case BLOCK_COMMENT -> {
          if (previous == '*' && current == '/') state = LexState.CODE;
          buffer.append(current);
        }
        case SINGLE_QUOTE -> {
          if (current == '\'') state = LexState.CODE;
          buffer.append(current);
        }
        case DOUBLE_QUOTE -> {
          if (current == '"') state = LexState.CODE;
          buffer.append(current);
        }
        case CODE -> appendSqlCharacter(current);
      }
      previous = current;
    }

    /** 只有普通 SQL 区域的分号可以结束语句；注释起始字符仍原样保留在缓冲区。 */
    private void appendSqlCharacter(char current) {
      if (previous == '-' && current == '-') {
        state = LexState.LINE_COMMENT;
        buffer.append(current);
      } else if (previous == '/' && current == '*') {
        state = LexState.BLOCK_COMMENT;
        buffer.append(current);
      } else if (current == '\'') {
        state = LexState.SINGLE_QUOTE;
        buffer.append(current);
      } else if (current == '"') {
        state = LexState.DOUBLE_QUOTE;
        buffer.append(current);
      } else if (current == ';') {
        String rawStatement = buffer.toString();
        String statement = rawStatement.trim();
        if (!EmptyChecks.isEmpty(statement)) statements.add(statement);
        buffer.setLength(0);
      } else {
        buffer.append(current);
      }
    }

    private List<String> complete() {
      String rawTail = buffer.toString();
      String tail = rawTail.trim();
      if (!EmptyChecks.isEmpty(tail)) statements.add(tail);
      return statements;
    }
  }

  @Override
  protected Map<String, Object> asOutput(Map<String, Object> result) {
    return result;
  }

  private String readSql(SdkTaskContext ctx) {
    Object raw = ctx.parameters().get(SQL_PARAM);
    if (!(raw instanceof String sql) || sql.isBlank()) {
      throw new IllegalArgumentException("missing required parameter 'sql'");
    }
    return sql;
  }

  private void assertNotOsCapableRole(Connection conn) throws SQLException {
    try (Statement probe = conn.createStatement();
        ResultSet rs = probe.executeQuery(OS_CAPABLE_ROLE_PROBE)) {
      if (rs.next() && rs.getBoolean(1)) {
        throw new SecurityException("refusing SQL on OS-capable DB role");
      }
    }
  }

  private Map<String, Object> readResultSet(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int columnCount = meta.getColumnCount();
    List<Map<String, Object>> rows = new ArrayList<>();
    boolean truncated = false;
    while (rs.next()) {
      if (rows.size() >= config.maxResultRows()) {
        truncated = true;
        break;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= columnCount; i++) {
        row.put(meta.getColumnLabel(i), rs.getObject(i));
      }
      rows.add(row);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resultSet", rows);
    out.put("rowCount", rows.size());
    out.put("truncated", truncated);
    return out;
  }
}
