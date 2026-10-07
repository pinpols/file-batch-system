package io.github.pinpols.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** {@link SqlTaskExecutor} 单测 — validation / SQL split / type 识别 / 执行路径(mocked JDBC)。 */
@DisplayName("数据库执行器: 参数校验, 语句切分, 类型识别与执行路径")
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
  @DisplayName("参数校验: 语句, 超时与敏感凭据的准入判定")
  class Validation {

    @Test
    @DisplayName("缺少语句时执行应失败, 并提示参数必填")
    void shouldReject_whenSqlMissing() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.sql required");
    }

    @Test
    @DisplayName("语句为纯空白时应判为缺失并失败")
    void shouldReject_whenSqlBlank() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("sql", "   ")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.sql required");
    }

    @Test
    @DisplayName("语句条数超过上限时应拒绝执行, 并说明数量越界")
    void shouldReject_whenStatementsExceedLimit() {
      props.setMaxStatementsPerJob(2);
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "SELECT 1; SELECT 2; SELECT 3;")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("too many statements");
    }

    @Test
    @DisplayName("语句超时非正数时应拒绝执行")
    void shouldReject_whenStatementTimeoutNotPositive() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "SELECT 1", "statementTimeoutSeconds", 0)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("statementTimeoutSeconds must be positive");
    }

    @Test
    @DisplayName("参数携带敏感凭据字段时应被凭据闸门拒绝, 并返回敏感数据标识")
    void rejectsSensitiveCredentialInParameters_LaneC() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("sql", "SELECT 1", "db_password", "leak")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("SENSITIVE_DATA_IN_PARAMETERS");
    }
  }

  // ─── SQL split ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("语句切分: 引号, 注释与美元引用内的分号不误切")
  class SplitStatements {

    @Test
    @DisplayName("语句末尾没有分号时应作为单条语句返回")
    void shouldReturnSingleStatement_whenNoSemicolon() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1")).containsExactly("SELECT 1");
    }

    @Test
    @DisplayName("多条语句应按分号切分为有序列表")
    void shouldSplit_whenMultipleStatementsPresent() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1; SELECT 2; SELECT 3;"))
          .containsExactly("SELECT 1", "SELECT 2", "SELECT 3");
    }

    @Test
    @DisplayName("单引号字面量内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideSingleQuotes() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 'a;b'; SELECT 2"))
          .containsExactly("SELECT 'a;b'", "SELECT 2");
    }

    @Test
    @DisplayName("双引号标识符内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideDoubleQuotes() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT \"col;name\" FROM t; SELECT 2"))
          .containsExactly("SELECT \"col;name\" FROM t", "SELECT 2");
    }

    @Test
    @DisplayName("行注释内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideLineComment() {
      assertThat(SqlTaskExecutor.splitStatements("""
          SELECT 1 -- comment ; not split
          ; SELECT 2
          """.stripTrailing())).hasSize(2);
    }

    @Test
    @DisplayName("块注释内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideBlockComment() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1 /* comment ; not split */; SELECT 2"))
          .hasSize(2);
    }

    @Test
    @DisplayName("连续分号产生的空语句应被跳过, 只保留有效语句")
    void shouldSkipEmptyStatements_whenConsecutiveSemicolons() {
      assertThat(SqlTaskExecutor.splitStatements("SELECT 1;;;SELECT 2;"))
          .containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    @DisplayName("美元引用体内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideDollarQuotedBody() {
      // $$...$$ 体内的 ; 不应被误切(PG 函数体/含分号字面量常见)
      assertThat(SqlTaskExecutor.splitStatements("SELECT $$a;b;c$$ AS v; SELECT 2"))
          .containsExactly("SELECT $$a;b;c$$ AS v", "SELECT 2");
    }

    @Test
    @DisplayName("带标签的美元引用体内的分号不应触发切分")
    void shouldKeepSemicolon_whenInsideTaggedDollarQuote() {
      assertThat(SqlTaskExecutor.splitStatements(
              "DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$; SELECT 9"))
          .containsExactly("DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$", "SELECT 9");
    }

    @Test
    @DisplayName("位置参数形式不应被误判为美元引用, 分号仍应正常切分")
    void shouldSplit_whenDollarIsPositionalParam() {
      // $1 是位置参数,不是 dollar-quote 开标签;此处 ; 仍应正常切分
      assertThat(SqlTaskExecutor.splitStatements("SELECT $1 WHERE a=$1; SELECT 2"))
          .containsExactly("SELECT $1 WHERE a=$1", "SELECT 2");
    }
  }

  // ─── Type detection ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("语句类型识别: 查询, 写入, 结构变更与事务语句归类")
  class TypeDetection {

    @Test
    @DisplayName("查询类语句应归类为查询类型, 包含公共表表达式与执行计划语句")
    void shouldClassifyAsSelect_whenQueryLikeStatement() {
      assertThat(SqlTaskExecutor.detectStatementType("SELECT * FROM t")).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("WITH x AS (SELECT 1) SELECT * FROM x"))
          .isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("EXPLAIN SELECT 1")).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("SHOW TABLES")).isEqualTo("SELECT");
    }

    @Test
    @DisplayName("插入, 更新, 删除与合并语句应归类为数据变更类型")
    void shouldClassifyAsWrite_whenDataChangeStatement() {
      assertThat(SqlTaskExecutor.detectStatementType("INSERT INTO t VALUES (1)"))
          .isEqualTo("INSERT");
      assertThat(SqlTaskExecutor.detectStatementType("UPDATE t SET x = 1")).isEqualTo("UPDATE");
      assertThat(SqlTaskExecutor.detectStatementType("DELETE FROM t")).isEqualTo("DELETE");
      assertThat(SqlTaskExecutor.detectStatementType("MERGE INTO t USING s")).isEqualTo("UPSERT");
    }

    @Test
    @DisplayName("建表, 改表, 删索引, 清表与授权语句应归类为结构变更类型")
    void shouldClassifyAsDdl_whenSchemaChangeStatement() {
      assertThat(SqlTaskExecutor.detectStatementType("CREATE TABLE t (id INT)")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("ALTER TABLE t ADD col INT"))
          .isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("DROP INDEX i")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("TRUNCATE TABLE t")).isEqualTo("DDL");
      assertThat(SqlTaskExecutor.detectStatementType("GRANT SELECT ON t TO u")).isEqualTo("DDL");
    }

    @Test
    @DisplayName("过程调用语句应归类为调用类型")
    void shouldClassifyAsCall_whenProcedureInvocation() {
      assertThat(SqlTaskExecutor.detectStatementType("CALL proc(?, ?)")).isEqualTo("CALL");
      assertThat(SqlTaskExecutor.detectStatementType("EXEC proc")).isEqualTo("CALL");
    }

    @Test
    @DisplayName("事务控制语句应归类为事务类型")
    void shouldClassifyAsTransaction_whenTransactionControlStatement() {
      assertThat(SqlTaskExecutor.detectStatementType("BEGIN")).isEqualTo("TX");
      assertThat(SqlTaskExecutor.detectStatementType("COMMIT")).isEqualTo("TX");
      assertThat(SqlTaskExecutor.detectStatementType("ROLLBACK")).isEqualTo("TX");
    }

    @Test
    @DisplayName("识别语句类型时应跳过前置注释与空白")
    void shouldIgnoreLeadingComment_whenDetectingType() {
      assertThat(SqlTaskExecutor.detectStatementType("""
          -- header
            SELECT 1
          """.stripTrailing())).isEqualTo("SELECT");
      assertThat(SqlTaskExecutor.detectStatementType("/* block */  UPDATE t SET x = 1"))
          .isEqualTo("UPDATE");
    }

    @Test
    @DisplayName("未闭合的块注释应在限定时间内返回未知类型, 不出现回溯卡死")
    void shouldReturnUnknown_whenBlockCommentUnterminated() {
      String statement = "/*" + "*".repeat(100_000);
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(2),
          () -> assertThat(SqlTaskExecutor.detectStatementType(statement)).isEqualTo("UNKNOWN"));
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("能力声明应反映配置: 任务类型为数据库执行, 占用数据库资源且可取消, 非幂等")
  void shouldExposeCapability_whenExecutorConfigured() {
    assertThat(executor.taskType()).isEqualTo("sql");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse(); // 保守
    assertThat(executor.capability().cancellable()).isTrue();
  }

  // ─── Execution (mocked JDBC) ────────────────────────────────────────────────

  @Nested
  @DisplayName("执行路径: 结果集, 影响行数与事务提交回滚")
  class MockedExecution {

    @Test
    @DisplayName("执行查询时应返回结果集行数与数据内容, 并设置语句超时")
    void shouldReturnResultSet_whenSelectExecutes() throws Exception {
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
      assertThat(r.output().get("lastResultSet")).asInstanceOf(LIST).hasSize(1);

      verify(stmt).setQueryTimeout(30); // default 30s
    }

    @Test
    @DisplayName("执行写语句时应返回影响行数, 自动提交模式下不额外提交")
    void shouldReturnAffectedRows_whenUpdateExecutes() throws Exception {
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
    @DisplayName("关闭自动提交时执行成功应显式提交, 且不回滚")
    void shouldCommit_whenAutoCommitDisabled() throws Exception {
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
    @DisplayName("关闭自动提交时执行失败应回滚, 且不提交")
    void shouldRollback_whenAutoCommitDisabledAndExecutionFails() throws Exception {
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
