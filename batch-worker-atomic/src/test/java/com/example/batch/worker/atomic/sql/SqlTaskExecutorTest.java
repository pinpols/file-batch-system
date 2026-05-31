package com.example.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** {@link SqlTaskExecutor} 单测 — validation / SQL split / type 识别 / 执行路径(mocked JDBC)。 */
class SqlTaskExecutorTest {

  private SqlExecutorProperties props;
  private DataSource ds;
  private BeanFactory beanFactory;
  private SqlTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(false); // mock 连接/superuser;角色闸拒绝路径由真 PG IT 验
    ds = mock(DataSource.class);
    beanFactory = mock(BeanFactory.class);
    executor = new SqlTaskExecutor(props, beanFactory, ds);
  }

  private TaskContext ctxWithParams(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Nested
  class Validation {

    @Test
    void rejectsMissingSql() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.sql required");
    }

    @Test
    void rejectsBlankSql() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "   ")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.sql required");
    }

    @Test
    void rejectsStatementTypeNotInWhitelist() {
      // 默认只允许 SELECT
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "DELETE FROM users WHERE id = 1")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("DELETE not in allowedStatementTypes");
    }

    @Test
    void rejectsDdlWhenDdlWhitelistEmpty() {
      props.setAllowedStatementTypes(Set.of("DDL"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "DROP TABLE users")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("DDL not allowed");
    }

    @Test
    void rejectsDdlNotInDdlWhitelist() {
      props.setAllowedStatementTypes(Set.of("DDL"));
      props.setDdlWhitelist(Set.of("CREATE INDEX", "ALTER TABLE"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "DROP TABLE users")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("DDL not in ddlWhitelist");
    }

    @Test
    void rejectsTooManyStatements() {
      props.setMaxStatementsPerJob(2);
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "SELECT 1; SELECT 2; SELECT 3;")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("too many statements");
    }

    @Test
    void rejectsNonPositiveTimeout() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "SELECT 1", "statementTimeoutSeconds", 0)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("statementTimeoutSeconds must be positive");
    }
  }

  // ─── SQL split ──────────────────────────────────────────────────────────────

  @Nested
  class SplitStatements {

    @Test
    void singleStatementNoSemicolon() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1")).containsExactly("SELECT 1");
    }

    @Test
    void multipleStatements() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1; SELECT 2; SELECT 3;"))
          .containsExactly("SELECT 1", "SELECT 2", "SELECT 3");
    }

    @Test
    void ignoresSemicolonInSingleQuotes() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 'a;b'; SELECT 2"))
          .containsExactly("SELECT 'a;b'", "SELECT 2");
    }

    @Test
    void ignoresSemicolonInDoubleQuotes() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT \"col;name\" FROM t; SELECT 2"))
          .containsExactly("SELECT \"col;name\" FROM t", "SELECT 2");
    }

    @Test
    void ignoresSemicolonInLineComment() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1 -- comment ; not split\n; SELECT 2"))
          .hasSize(2);
    }

    @Test
    void ignoresSemicolonInBlockComment() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1 /* comment ; not split */; SELECT 2"))
          .hasSize(2);
    }

    @Test
    void skipsEmptyStatements() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1;;;SELECT 2;"))
          .containsExactly("SELECT 1", "SELECT 2");
    }
  }

  // ─── Type detection ─────────────────────────────────────────────────────────

  @Nested
  class TypeDetection {

    @Test
    void detectsSelect() {
      assertThat(SqlTaskExecutor.detectStatementType("SELECT * FROM t")).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("WITH x AS (SELECT 1) SELECT * FROM x"))
          .isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("EXPLAIN SELECT 1")).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("SHOW TABLES")).isEqualTo("SELECT");
    }

    @Test
    void detectsDml() {
      assertThat(SqlTaskExecutor.detectStatementType("INSERT INTO t VALUES (1)"))
          .isEqualTo("INSERT");
      assertThat(SqlTaskExecutor.detectStatementType("UPDATE t SET x = 1")).isEqualTo("UPDATE");
      assertThat(SqlTaskExecutor.detectStatementType("DELETE FROM t")).isEqualTo("DELETE");
      assertThat(SqlTaskExecutor.detectStatementType("MERGE INTO t USING s")).isEqualTo("UPSERT");
    }

    @Test
    void detectsDdl() {
      assertThat(SqlTaskExecutor.detectStatementType("CREATE TABLE t (id INT)")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("ALTER TABLE t ADD col INT")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("DROP INDEX i")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("TRUNCATE TABLE t")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("GRANT SELECT ON t TO u")).isEqualTo("DDL");
    }

    @Test
    void detectsCall() {
      assertThat(SqlTaskExecutor.detectStatementType("CALL proc(?, ?)")).isEqualTo("CALL");
      assertThat(SqlTaskExecutor.detectStatementType("EXEC proc")).isEqualTo("CALL");
    }

    @Test
    void detectsTx() {
      assertThat(SqlTaskExecutor.detectStatementType("BEGIN")).isEqualTo("TX");
      assertThat(SqlTaskExecutor.detectStatementType("COMMIT")).isEqualTo("TX");
      assertThat(SqlTaskExecutor.detectStatementType("ROLLBACK")).isEqualTo("TX");
    }

    @Test
    void skipsLeadingCommentAndWhitespace() {
      assertThat(SqlTaskExecutor.detectStatementType("-- header\n  SELECT 1")).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("/* block */  UPDATE t SET x = 1"))
          .isEqualTo("UPDATE");
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  void capabilityReflectsConfig() {
    assertThat(executor.taskType()).isEqualTo("sql");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse(); // 保守
    assertThat(executor.capability().cancellable()).isTrue();
  }

  // ─── Execution (mocked JDBC) ────────────────────────────────────────────────

  @Nested
  class MockedExecution {

    @Test
    void runsSelectAndReturnsResultSet() throws Exception {
      // setup mocks: Connection → Statement → ResultSet 单行 (id=1)
      Connection conn = mock(Connection.class);
      Statement stmt = mock(Statement.class);
      ResultSet rs = mock(ResultSet.class);
      ResultSetMetaData md = mock(ResultSetMetaData.class);

      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.createStatement()).thenReturn(stmt);
      when(stmt.execute(anyString())).thenReturn(true);
      when(stmt.getResultSet()).thenReturn(rs);
      when(rs.getMetaData()).thenReturn(md);
      when(md.getColumnCount()).thenReturn(1);
      when(md.getColumnLabel(1)).thenReturn("id");
      when(rs.next()).thenReturn(true, false);
      when(rs.getObject(1)).thenReturn(1);

      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "SELECT id FROM t")));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("statementCount", 1);
      assertThat(r.output()).containsEntry("lastResultRows", 1);
      assertThat(r.output().get("lastResultSet")).asList().hasSize(1);

      verify(stmt).setQueryTimeout(30); // default 30s
    }

    @Test
    void runsUpdateAndReturnsAffectedRows() throws Exception {
      props.setAllowedStatementTypes(Set.of("UPDATE"));
      Connection conn = mock(Connection.class);
      Statement stmt = mock(Statement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.createStatement()).thenReturn(stmt);
      when(stmt.execute(anyString())).thenReturn(false);
      when(stmt.getUpdateCount()).thenReturn(7);

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "UPDATE t SET x = 1", "autoCommit", true)));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("totalAffectedRows", 7L);
      // autoCommit=true → 不 commit / 不 rollback
      verify(conn, never()).commit();
    }

    @Test
    void commitsOnSuccessWhenAutoCommitFalse() throws Exception {
      props.setAllowedStatementTypes(Set.of("UPDATE"));
      Connection conn = mock(Connection.class);
      Statement stmt = mock(Statement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.createStatement()).thenReturn(stmt);
      when(stmt.execute(anyString())).thenReturn(false);
      when(stmt.getUpdateCount()).thenReturn(3);

      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "UPDATE t SET x = 1")));

      assertThat(r.success()).isTrue();
      verify(conn).commit();
      verify(conn, never()).rollback();
    }

    @Test
    void rollbacksOnFailureWhenAutoCommitFalse() throws Exception {
      props.setAllowedStatementTypes(Set.of("UPDATE"));
      Connection conn = mock(Connection.class);
      Statement stmt = mock(Statement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.createStatement()).thenReturn(stmt);
      when(stmt.execute(anyString())).thenThrow(new SQLException("syntax error"));

      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "UPDATE t SET x = 1")));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("syntax error");
      verify(conn).rollback();
      verify(conn, never()).commit();
    }
  }
}
