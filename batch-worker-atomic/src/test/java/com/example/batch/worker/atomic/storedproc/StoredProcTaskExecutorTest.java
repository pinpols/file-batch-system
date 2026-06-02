package com.example.batch.worker.atomic.storedproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** {@link StoredProcTaskExecutor} 单测 — validation / 类型映射 / mocked CALL 执行。 */
class StoredProcTaskExecutorTest {

  private StoredProcExecutorProperties props;
  private DataSource ds;
  private BeanFactory beanFactory;
  private StoredProcTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new StoredProcExecutorProperties();
    props.setForbidOsCapableRole(false); // 单测 mock 连接,新 DB 闸由真 PG IT 覆盖
    props.setAllowSecurityDefiner(true);
    props.setEnabled(true);
    ds = mock(DataSource.class);
    beanFactory = mock(BeanFactory.class);
    executor = new StoredProcTaskExecutor(props, beanFactory, ds);
  }

  private TaskContext ctxWithParams(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Nested
  class Validation {

    @Test
    void rejectsMissingProcedureName() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.procedureName required");
    }

    @Test
    void rejectsInvalidProcedureNameChars() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "drop;table")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("must match");
    }

    @Test
    void rejectsSensitiveCredentialInParameters_LaneC() {
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("procedureName", "batch.foo", "client_secret", "leak")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("SENSITIVE_DATA_IN_PARAMETERS");
    }

    @Test
    void allowsBySchemaWhenSchemaAllowlisted() throws Exception {
      // schema 级放行:allowedSchemas 含 batch → batch.* 任意过程都过 validation,无需逐个列举
      props.setAllowedSchemas(Set.of("batch"));
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "batch.brand_new_proc")));
      assertThat(r.success()).isTrue();
    }

    @Test
    void rejectsSchemaOutsideAllowedSchemas() {
      // schema 级放行挡住逃逸 schema:allowedSchemas=batch 时 pg_catalog.* 拒绝
      props.setAllowedSchemas(Set.of("batch"));
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "pg_catalog.evil_proc")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not allowed");
    }

    @Test
    void allowsSchemaQualifiedName() throws Exception {
      // 应通过 validation,真 SQL 执行用 mock(只测到能进 runCall)
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "batch.refresh_metrics")));
      assertThat(r.success()).isTrue();
    }

    @Test
    void rejectsNonListInParams() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "p", "inParams", "not-a-list")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("inParams must be a list");
    }

    @Test
    void rejectsBadOutType() {
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("procedureName", "p", "outParams", List.of("STRUCT"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedOutSqlTypes");
    }

    @Test
    void rejectsNonPositiveTimeout() {
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("procedureName", "p", "statementTimeoutSeconds", -1)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("must be positive");
    }
  }

  // ─── Type mapping ────────────────────────────────────────────────────────────

  @Nested
  class TypeMapping {

    @Test
    void mapsCommonTypes() {
      assertThat(StoredProcTaskExecutor.toSqlType("BIGINT")).isEqualTo(Types.BIGINT);
      assertThat(StoredProcTaskExecutor.toSqlType("VARCHAR")).isEqualTo(Types.VARCHAR);
      assertThat(StoredProcTaskExecutor.toSqlType("TIMESTAMP")).isEqualTo(Types.TIMESTAMP);
      assertThat(StoredProcTaskExecutor.toSqlType("REF_CURSOR")).isEqualTo(Types.REF_CURSOR);
      assertThat(StoredProcTaskExecutor.toSqlType("OTHER")).isEqualTo(Types.OTHER);
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  void capabilityReflectsConfig() {
    assertThat(executor.taskType()).isEqualTo("stored_proc");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse();
  }

  // ─── Execution (mocked) ────────────────────────────────────────────────────

  @Nested
  class MockedExecution {

    @Test
    void callsProcedureWithCorrectPlaceholders() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of(
                      "procedureName", "batch.proc",
                      "inParams", List.of(1, "x"),
                      "outParams", List.of("INTEGER", "VARCHAR"))));

      assertThat(r.success()).isTrue();
      // 2 in + 2 out = 4 placeholders
      verify(conn).prepareCall("{call batch.proc(?,?,?,?)}");
      verify(cs).setObject(1, 1);
      verify(cs).setObject(2, "x");
      verify(cs).registerOutParameter(3, Types.INTEGER);
      verify(cs).registerOutParameter(4, Types.VARCHAR);
      verify(cs).execute();
    }

    @Test
    void readsOutValuesIntoOutput() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.getObject(1)).thenReturn(42);
      when(cs.getObject(2)).thenReturn("hello");

      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("procedureName", "p", "outParams", List.of("INTEGER", "VARCHAR"))));

      assertThat(r.success()).isTrue();
      @SuppressWarnings("unchecked")
      Map<String, Object> outValues = (Map<String, Object>) r.output().get("outValues");
      assertThat(outValues).containsEntry("p1", 42).containsEntry("p2", "hello");
    }

    @Test
    void commitsOnSuccessWhenAutoCommitFalse() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "p")));

      assertThat(r.success()).isTrue();
      verify(conn).commit();
    }

    @Test
    void rollbacksOnFailureWhenAutoCommitFalse() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.execute()).thenThrow(new SQLException("call failed"));

      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "p")));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("call failed");
      verify(conn).rollback();
    }

    @Test
    void truncatesLargeStringOutParam() throws Exception {
      props.setMaxOutBytesPerParam(10);
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.getObject(1)).thenReturn("0123456789ABCDEFGH");

      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("procedureName", "p", "outParams", List.of("VARCHAR"))));

      @SuppressWarnings("unchecked")
      Map<String, Object> out = (Map<String, Object>) r.output().get("outValues");
      assertThat((String) out.get("p1")).startsWith("0123456789").endsWith("<truncated>");
    }
  }
}
