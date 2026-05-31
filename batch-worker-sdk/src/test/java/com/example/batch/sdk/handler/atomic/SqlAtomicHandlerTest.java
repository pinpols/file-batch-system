package com.example.batch.sdk.handler.atomic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SqlAtomicHandler — ADR-036 SQL Atomic 开箱即用模板")
class SqlAtomicHandlerTest {

  private DataSource dataSource;
  private Connection connection;
  private Statement statement;

  @BeforeEach
  void wireConnection() throws Exception {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    statement = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(connection);
  }

  private static SdkTaskContext ctx(String sql) {
    return new SdkTaskContext("tx", "j", "ti", 1L, "w-1", Map.of("sql", sql), Map.of());
  }

  private static SdkTaskContext ctxNoSql() {
    return new SdkTaskContext("tx", "j", "ti", 1L, "w-1", Map.of(), Map.of());
  }

  /** 角色闸放行:probe 返回 false(非 OS-capable),业务 statement 走第二次 createStatement。 */
  private void stubRoleGateAllows() throws Exception {
    Statement probe = mock(Statement.class);
    ResultSet probeRs = mock(ResultSet.class);
    when(probe.executeQuery(anyString())).thenReturn(probeRs);
    when(probeRs.next()).thenReturn(true);
    when(probeRs.getBoolean(1)).thenReturn(false);
    when(connection.createStatement()).thenReturn(probe).thenReturn(statement);
  }

  @Test
  @DisplayName("SELECT → resultSet + rowCount,success")
  void shouldReturnResultSet_whenSelect() throws Exception {
    // arrange
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(true);
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(statement.getResultSet()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("n");
    when(rs.next()).thenReturn(true, false);
    when(rs.getObject(1)).thenReturn(1);

    // act
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctx("SELECT 1"));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("rowCount", 1).containsEntry("truncated", false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.output().get("resultSet");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("n", 1);
  }

  @Test
  @DisplayName("UPDATE(execute 返 false)→ affectedRows,success")
  void shouldReturnAffectedRows_whenUpdate() throws Exception {
    // arrange
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(7);

    // act
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource)
            .execute(ctx("UPDATE t SET x=1"));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("affectedRows", 7);
  }

  @Test
  @DisplayName("缺 sql 参数 → execute fail,message 含 'sql'")
  void shouldFail_whenSqlParamMissing() {
    // act
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctxNoSql());

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("sql");
  }

  @Test
  @DisplayName("forbidOsCapableRole=true 且角色闸命中 → SecurityException → execute fail")
  void shouldFail_whenRoleIsOsCapable() throws Exception {
    // arrange
    Statement probe = mock(Statement.class);
    ResultSet probeRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(probe);
    when(probe.executeQuery(anyString())).thenReturn(probeRs);
    when(probeRs.next()).thenReturn(true);
    when(probeRs.getBoolean(1)).thenReturn(true);

    // act
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctx("SELECT 1"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("OS-capable");
    verify(statement, never()).execute(anyString());
  }

  @Test
  @DisplayName("forbidOsCapableRole=false → 跳过角色闸(不查 pg_roles)")
  void shouldSkipRoleGate_whenDisabled() throws Exception {
    // arrange
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(0);
    var cfg = new SqlAtomicConfig("sql", 30, 10000, false);

    // act
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    // assert
    assertThat(result.success()).isTrue();
    verify(connection, times(1)).createStatement();
  }

  @Test
  @DisplayName("maxResultRows 截断:超上限 → truncated=true,rowCount=上限")
  void shouldTruncate_whenRowsExceedMax() throws Exception {
    // arrange
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(true);
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(statement.getResultSet()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("n");
    when(rs.next()).thenReturn(true);
    when(rs.getObject(1)).thenReturn(9);
    var cfg = new SqlAtomicConfig("sql", 30, 2, true);

    // act
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("SELECT * FROM big"));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("rowCount", 2).containsEntry("truncated", true);
  }

  @Test
  @DisplayName("setQueryTimeout 按配置被调用")
  void shouldSetQueryTimeout_fromConfig() throws Exception {
    // arrange
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(0);
    var cfg = new SqlAtomicConfig("sql", 45, 10000, true);

    // act
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    // assert
    assertThat(result.success()).isTrue();
    verify(statement).setQueryTimeout(45);
  }

  @Test
  @DisplayName("taskType 来自 config")
  void shouldExposeTaskType_fromConfig() {
    var handler = new SqlAtomicHandler(SqlAtomicConfig.defaults("tenant_sql"), dataSource);
    assertThat(handler.taskType()).isEqualTo("tenant_sql");
  }
}
