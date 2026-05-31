package com.example.batch.sdk.handler.atomic;

import com.example.batch.sdk.handler.SdkAbstractAtomicHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

  private static final String OS_CAPABLE_ROLE_PROBE =
      "select rolsuper"
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
    try (Connection conn = dataSource.getConnection()) {
      if (config.forbidOsCapableRole()) {
        assertNotOsCapableRole(conn);
      }
      try (Statement st = conn.createStatement()) {
        st.setQueryTimeout(config.statementTimeoutSeconds());
        boolean hasRs = st.execute(sql);
        if (hasRs) {
          try (ResultSet rs = st.getResultSet()) {
            return readResultSet(rs);
          }
        }
        return Map.of("affectedRows", st.getUpdateCount());
      }
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

  private void assertNotOsCapableRole(Connection conn) throws Exception {
    try (Statement probe = conn.createStatement();
        ResultSet rs = probe.executeQuery(OS_CAPABLE_ROLE_PROBE)) {
      if (rs.next() && rs.getBoolean(1)) {
        throw new SecurityException("refusing SQL on OS-capable DB role");
      }
    }
  }

  private Map<String, Object> readResultSet(ResultSet rs) throws Exception {
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
