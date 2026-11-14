package io.github.pinpols.batch.sdk.handler.atomic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  @DisplayName("查询成功时返回结果集和行数")
  void shouldReturnResultSet_whenSelect() throws Exception {
    // 准备
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

    // 执行
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctx("SELECT 1"));

    // 断言
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
    // 准备
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(7);

    // 执行
    SdkTaskResult result = new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource)
        .execute(ctx("UPDATE t SET x=1"));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("affectedRows", 7L);
  }

  @Test
  @DisplayName("缺 sql 参数 → execute fail,message 含 'sql'")
  void shouldFail_whenSqlParamMissing() {
    // 执行
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctxNoSql());

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("sql");
  }

  @Test
  @DisplayName("forbidOsCapableRole=true 且角色闸命中 → SecurityException → execute fail")
  void shouldFail_whenRoleIsOsCapable() throws Exception {
    // 准备
    Statement probe = mock(Statement.class);
    ResultSet probeRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(probe);
    when(probe.executeQuery(anyString())).thenReturn(probeRs);
    when(probeRs.next()).thenReturn(true);
    when(probeRs.getBoolean(1)).thenReturn(true);

    // 执行
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctx("SELECT 1"));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("OS-capable");
    verify(statement, never()).execute(anyString());
  }

  @Test
  @DisplayName("forbidOsCapableRole=false → 跳过角色闸(不查 pg_roles)")
  void shouldSkipRoleGate_whenDisabled() throws Exception {
    // 准备
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(0);
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, false, false);

    // 执行
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    // 断言
    assertThat(result.success()).isTrue();
    verify(connection, times(1)).createStatement();
  }

  @Test
  @DisplayName("maxResultRows 截断:超上限 → truncated=true,rowCount=上限")
  void shouldTruncate_whenRowsExceedMax() throws Exception {
    // 准备
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
    var cfg = new SqlAtomicConfig("sql", 30, 2, 50, false, true);

    // 执行
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("SELECT * FROM big"));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("rowCount", 2).containsEntry("truncated", true);
  }

  @Test
  @DisplayName("setQueryTimeout 按配置被调用")
  void shouldSetQueryTimeout_fromConfig() throws Exception {
    // 准备
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(0);
    var cfg = new SqlAtomicConfig("sql", 45, 10000, 50, false, true);

    // 执行
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    // 断言
    assertThat(result.success()).isTrue();
    verify(statement).setQueryTimeout(45);
  }

  @Test
  @DisplayName("taskType 来自 config")
  void shouldExposeTaskType_fromConfig() {
    var handler = new SqlAtomicHandler(SqlAtomicConfig.defaults("tenant_sql"), dataSource);
    assertThat(handler.taskType()).isEqualTo("tenant_sql");
  }

  // ─── 对齐平台:maxStatementsPerJob + 多语句 + autoCommit ──────────────────────

  @Test
  @DisplayName("超 maxStatementsPerJob → fail(不执行任何语句)")
  void shouldRejectTooManyStatements_whenExceedMax() throws Exception {
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 2, false, true);
    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource)
        .execute(ctx("UPDATE a SET x=1; UPDATE b SET y=2; UPDATE c SET z=3;"));
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("too many statements");
    verify(connection, never()).createStatement();
  }

  @Test
  @DisplayName("只有分号与空白时 → fail(无可执行语句)")
  void shouldReject_whenNoExecutableStatement() throws Exception {
    SdkTaskResult result =
        new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource).execute(ctx(" ; ; "));
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("no executable SQL statement");
    verify(connection, never()).createStatement();
  }

  @Test
  @DisplayName("多语句逐条执行 + affectedRows 聚合 + statementCount")
  void shouldRunMultipleStatements_andAggregate() throws Exception {
    Statement probe = mock(Statement.class);
    ResultSet probeRs = mock(ResultSet.class);
    when(probe.executeQuery(anyString())).thenReturn(probeRs);
    when(probeRs.next()).thenReturn(true);
    when(probeRs.getBoolean(1)).thenReturn(false);
    Statement st1 = mock(Statement.class);
    Statement st2 = mock(Statement.class);
    when(st1.execute(anyString())).thenReturn(false);
    when(st1.getUpdateCount()).thenReturn(3);
    when(st2.execute(anyString())).thenReturn(false);
    when(st2.getUpdateCount()).thenReturn(4);
    when(connection.createStatement()).thenReturn(probe).thenReturn(st1).thenReturn(st2);

    SdkTaskResult result = new SqlAtomicHandler(SqlAtomicConfig.defaults("sql"), dataSource)
        .execute(ctx("UPDATE a SET x=1; UPDATE b SET y=2;"));

    assertThat(result.success()).isTrue();
    assertThat(result.output())
        .containsEntry("statementCount", 2)
        .containsEntry("affectedRows", 7L);
  }

  @Test
  @DisplayName("defaultAutoCommit=false → 全部成功后 commit")
  void shouldCommit_whenAutoCommitFalse() throws Exception {
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(1);
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, false, true);

    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    assertThat(result.success()).isTrue();
    // 注:setAutoCommit(false) 会被调 2 次(进入设 + finally restore,mock 默认 originalAutoCommit=false),只验
    // commit
    verify(connection).commit();
  }

  @Test
  @DisplayName("defaultAutoCommit=false + 执行异常 → rollback")
  void shouldRollback_onErrorWhenAutoCommitFalse() throws Exception {
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenThrow(new SQLException("boom"));
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, false, true);

    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    assertThat(result.success()).isFalse();
    verify(connection).rollback();
  }

  @Test
  @DisplayName("defaultAutoCommit=false + 执行异常且 rollback 也失败 → 仍返回原始执行失败")
  void shouldKeepOriginalFailure_whenRollbackAlsoFails() throws Exception {
    stubRoleGateAllows();
    when(statement.execute(anyString())).thenThrow(new SQLException("boom"));
    doThrow(new SQLException("rollback failed")).when(connection).rollback();
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, false, true);

    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource).execute(ctx("UPDATE t SET x=1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("boom").doesNotContain("rollback failed");
    verify(connection).rollback();
  }

  // ─── 方言:按 DatabaseMetaData 选择切分词法 ─────────────────────────────────

  /** 连接报告为 MySQL,会话 sql_mode 为给定值;返回之后执行业务语句用的 Statement。 */
  private Statement stubMySqlConnection(String sqlMode) throws Exception {
    DatabaseMetaData meta = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(meta);
    when(meta.getDatabaseProductName()).thenReturn("MySQL");
    Statement modeQuery = mock(Statement.class);
    ResultSet modeRs = mock(ResultSet.class);
    when(modeQuery.executeQuery(anyString())).thenReturn(modeRs);
    when(modeRs.next()).thenReturn(true);
    when(modeRs.getString(1)).thenReturn(sqlMode);
    Statement business = mock(Statement.class);
    when(business.execute(anyString())).thenReturn(false);
    when(connection.createStatement()).thenReturn(modeQuery).thenReturn(business);
    return business;
  }

  @Test
  @DisplayName("连接为 MySQL 时单引号内的反斜杠转义引号, 分号不切断语句")
  void shouldUseMySqlBackslashEscapes_whenDatabaseIsMySql() throws Exception {
    Statement business = stubMySqlConnection("STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, true, false);

    SdkTaskResult result =
        new SqlAtomicHandler(cfg, dataSource).execute(ctx("insert into t values ('a\\';b')"));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("statementCount", 1);
    verify(business).execute("insert into t values ('a\\';b')");
  }

  @Test
  @DisplayName("MySQL 会话开启 NO_BACKSLASH_ESCAPES 时反斜杠按字面字符处理")
  void shouldTreatBackslashAsLiteral_whenMySqlNoBackslashEscapes() throws Exception {
    Statement business = stubMySqlConnection("NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES");
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, true, false);

    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource)
        .execute(ctx("insert into t values ('C:\\'); delete from t"));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("statementCount", 2);
    verify(business).execute("delete from t");
  }

  @Test
  @DisplayName("连接为 PostgreSQL 时按 PG 规则切分, 不查询 sql_mode")
  void shouldKeepPostgresRules_whenDatabaseIsPostgres() throws Exception {
    DatabaseMetaData meta = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(meta);
    when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenReturn(false);
    var cfg = new SqlAtomicConfig("sql", 30, 10000, 50, true, false);

    SdkTaskResult result = new SqlAtomicHandler(cfg, dataSource)
        .execute(ctx("insert into t values ('C:\\'); delete from t"));

    assertThat(result.output()).containsEntry("statementCount", 2);
    verify(statement, never()).executeQuery(anyString());
  }

  @Test
  @DisplayName("读取数据库产品名失败时回落到 PG 规则")
  void shouldFallBackToPostgresRules_whenMetadataUnavailable() throws Exception {
    when(connection.getMetaData()).thenThrow(new SQLException("no metadata"));

    assertThat(SqlSplitDialect.detect(connection)).isEqualTo(SqlSplitDialect.POSTGRESQL);
  }

  @Test
  @DisplayName("MariaDB 按 MySQL 规则切分; 读取 sql_mode 失败时按默认模式处理")
  void shouldUseMySqlDefaults_whenMariaDbSqlModeUnreadable() throws Exception {
    DatabaseMetaData meta = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(meta);
    when(meta.getDatabaseProductName()).thenReturn("MariaDB");
    when(connection.createStatement()).thenThrow(new SQLException("denied"));

    assertThat(SqlSplitDialect.detect(connection)).isEqualTo(SqlSplitDialect.mysql(""));
    assertThat(SqlSplitDialect.mysql(""))
        .returns(true, SqlSplitDialect::mysql)
        .returns(true, SqlSplitDialect::backslashEscapes)
        .returns(false, SqlSplitDialect::ansiQuotes);
  }

  @Test
  @DisplayName("MySQL 会话 sql_mode 无结果行时按默认模式处理")
  void shouldUseMySqlDefaults_whenSqlModeHasNoRow() throws Exception {
    DatabaseMetaData meta = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(meta);
    when(meta.getDatabaseProductName()).thenReturn("MySQL");
    Statement modeQuery = mock(Statement.class);
    ResultSet modeRs = mock(ResultSet.class);
    when(modeQuery.executeQuery(anyString())).thenReturn(modeRs);
    when(connection.createStatement()).thenReturn(modeQuery);

    assertThat(SqlSplitDialect.detect(connection)).isEqualTo(SqlSplitDialect.mysql(null));
  }

  @Test
  @DisplayName("ANSI_QUOTES 与 NO_BACKSLASH_ESCAPES 从 sql_mode 中识别, 大小写不敏感")
  void shouldParseSqlModeFlags() {
    assertThat(SqlSplitDialect.mysql("real_as_float,ansi_quotes,no_backslash_escapes"))
        .returns(false, SqlSplitDialect::backslashEscapes)
        .returns(true, SqlSplitDialect::ansiQuotes);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mySqlSplittingCases")
  @DisplayName("MySQL 词法: 反斜杠转义, 反引号标识符, # 与 -- 注释, 无美元引用")
  void shouldSplitWithMySqlRules(
      String scenario, SqlSplitDialect dialect, String sql, List<String> expected) {
    assertThat(dialect.split(sql)).containsExactlyElementsOf(expected);
  }

  private static Stream<Arguments> mySqlSplittingCases() {
    SqlSplitDialect byDefault = SqlSplitDialect.mysql("STRICT_TRANS_TABLES");
    SqlSplitDialect noBackslash = SqlSplitDialect.mysql("NO_BACKSLASH_ESCAPES");
    SqlSplitDialect ansiQuotes = SqlSplitDialect.mysql("ANSI_QUOTES");
    return Stream.of(
        Arguments.of(
            "backslash-escapes-single-quote",
            byDefault,
            "select 'it\\'s;ok'; select 2",
            List.of("select 'it\\'s;ok'", "select 2")),
        Arguments.of(
            "backslash-escapes-double-quote-string",
            byDefault,
            "select \"a\\\";b\"; select 2",
            List.of("select \"a\\\";b\"", "select 2")),
        Arguments.of(
            "escaped-backslash-then-close",
            byDefault,
            "select 'a\\\\'; select 2",
            List.of("select 'a\\\\'", "select 2")),
        Arguments.of(
            "doubled-quote",
            byDefault,
            "select 'it''s;ok'; select 2",
            List.of("select 'it''s;ok'", "select 2")),
        Arguments.of(
            "no-backslash-escapes",
            noBackslash,
            "insert into t values ('C:\\'); delete from t",
            List.of("insert into t values ('C:\\')", "delete from t")),
        Arguments.of(
            "ansi-quotes-identifier-backslash-literal",
            ansiQuotes,
            "select 1 as \"x\\\"; select 2",
            List.of("select 1 as \"x\\\"", "select 2")),
        Arguments.of(
            "ansi-quotes-single-quote-still-escapes",
            ansiQuotes,
            "select 'a\\';b'; select 2",
            List.of("select 'a\\';b'", "select 2")),
        Arguments.of(
            "backtick-identifier",
            byDefault,
            "select 1 as `a;b``c\\`; select 2",
            List.of("select 1 as `a;b``c\\`", "select 2")),
        Arguments.of(
            "hash-comment",
            byDefault,
            "select 1 # a;b\n; select 2",
            List.of("select 1 # a;b", "select 2")),
        Arguments.of(
            "dash-dash-space-comment",
            byDefault,
            "select 1 -- a;b\n; select 2",
            List.of("select 1 -- a;b", "select 2")),
        Arguments.of(
            "dash-dash-without-space-is-not-comment",
            byDefault,
            "select 1--1; select 2",
            List.of("select 1--1", "select 2")),
        Arguments.of("dash-dash-at-end", byDefault, "select 1; --", List.of("select 1", "--")),
        Arguments.of(
            "block-comment-does-not-nest",
            byDefault,
            "select 1 /* a /* b */ ; select 2",
            List.of("select 1 /* a /* b */", "select 2")),
        Arguments.of(
            "executable-comment-skipped",
            byDefault,
            "select 1 /*! ; */; select 2",
            List.of("select 1 /*! ; */", "select 2")),
        Arguments.of(
            "dollar-is-not-quote",
            byDefault,
            "select $$a; select 2$$",
            List.of("select $$a", "select 2$$")),
        Arguments.of(
            "unterminated-comment-and-quote",
            byDefault,
            "select 1; /* tail ; 'x",
            List.of("select 1", "/* tail ; 'x")),
        Arguments.of(
            "unterminated-quote-trailing-backslash",
            byDefault,
            "select 1; select 'a\\",
            List.of("select 1", "select 'a\\")),
        Arguments.of("empty", byDefault, " ; ;\n", List.of()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("statementSplittingCases")
  @DisplayName("只有引号和注释之外的分号切分 SQL，保留原始片段与非空尾句")
  void shouldPreserveStatementBoundaries_whenQuotesOrCommentsContainDelimiters(
      String scenario, String sql, List<String> expected) {
    List<String> statements = SqlAtomicHandler.splitStatements(sql);

    assertThat(statements).containsExactlyElementsOf(expected);
  }

  private static Stream<Arguments> statementSplittingCases() {
    return Stream.of(
        Arguments.of("empty", " ; ; \n", List.of()),
        Arguments.of("tail", " SELECT 1; SELECT 2 ", List.of("SELECT 1", "SELECT 2")),
        Arguments.of(
            "dollar-quoted-body",
            "SELECT $$a;b;c$$ AS v; SELECT 2",
            List.of("SELECT $$a;b;c$$ AS v", "SELECT 2")),
        Arguments.of(
            "tagged-dollar-quote",
            "DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$; SELECT 9",
            List.of("DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$", "SELECT 9")),
        Arguments.of(
            "positional-param-is-not-dollar-quote",
            "SELECT $1 WHERE a=$1; SELECT 2",
            List.of("SELECT $1 WHERE a=$1", "SELECT 2")),
        Arguments.of(
            "dollar-inside-single-quote",
            "SELECT '$$;'; SELECT 2",
            List.of("SELECT '$$;'", "SELECT 2")),
        Arguments.of(
            "single-quoted-semicolon", "SELECT ';';SELECT 2", List.of("SELECT ';'", "SELECT 2")),
        Arguments.of(
            "escaped-single-quote",
            "SELECT 'it''s;ok';SELECT 2",
            List.of("SELECT 'it''s;ok'", "SELECT 2")),
        Arguments.of(
            "double-quoted-semicolon",
            "SELECT \"semi;colon\";SELECT 2",
            List.of("SELECT \"semi;colon\"", "SELECT 2")),
        Arguments.of(
            "line-comment",
            "SELECT 1 -- ; still comment\r\n;SELECT 2",
            List.of("SELECT 1 -- ; still comment", "SELECT 2")),
        Arguments.of(
            "block-comment",
            "SELECT /* ; ' \" */ 1;SELECT 2",
            List.of("SELECT /* ; ' \" */ 1", "SELECT 2")),
        Arguments.of("trailing-comment", "SELECT 1;-- tail ;", List.of("SELECT 1", "-- tail ;")),
        Arguments.of(
            "unterminated-quote", "SELECT 'tail;still quote", List.of("SELECT 'tail;still quote")),
        Arguments.of("top-level-semicolon", "select 1; select 2", List.of("select 1", "select 2")),
        Arguments.of(
            "quotes-and-comments-mixed",
            "select ';' -- a;b\n; select \"x;y\" /* ; */",
            List.of("select ';' -- a;b", "select \"x;y\" /* ; */")),
        Arguments.of("drops-empty-statements", ";;select 1;", List.of("select 1")),
        // standard_conforming_strings=on(PG 默认): 普通字符串里的反斜杠是字面字符
        Arguments.of(
            "backslash-literal-in-standard-string",
            "insert into t values ('C:\\'); delete from t",
            List.of("insert into t values ('C:\\')", "delete from t")),
        Arguments.of(
            "backslash-literal-in-double-quotes",
            "select 1 as \"x\\\"; select 2",
            List.of("select 1 as \"x\\\"", "select 2")),
        Arguments.of(
            "doubled-quote-before-backslash",
            "select 'it''s;ok\\'; select 2",
            List.of("select 'it''s;ok\\'", "select 2")),
        // E'...' 转义字符串: 反斜杠转义下一个字符
        Arguments.of(
            "escape-string-backslash-quote",
            "select E'it\\'s;ok'; select 2",
            List.of("select E'it\\'s;ok'", "select 2")),
        Arguments.of(
            "escape-string-escaped-backslash",
            "select e'a\\\\'; select 2",
            List.of("select e'a\\\\'", "select 2")),
        Arguments.of(
            "escape-string-doubled-quote",
            "select E'it''s\\';x'; select 2",
            List.of("select E'it''s\\';x'", "select 2")),
        Arguments.of(
            "identifier-ending-with-e-is-not-escape-prefix",
            "select time'12:00\\'; select 2",
            List.of("select time'12:00\\'", "select 2")),
        Arguments.of(
            "backslash-in-dollar-quote-and-comments",
            "select $$a\\';$$ /* \\' ; */ -- \\' ;\n; select 2",
            List.of("select $$a\\';$$ /* \\' ; */ -- \\' ;", "select 2")),
        // PostgreSQL 块注释可嵌套: 内层收尾后仍在外层注释里
        Arguments.of(
            "nested-block-comment",
            "select 1 /* outer ; /* inner ; */ still comment ; */ ; select 2",
            List.of("select 1 /* outer ; /* inner ; */ still comment ; */", "select 2")),
        Arguments.of(
            "nested-block-comment-several-levels",
            "select 3 /* a /* b /* c ; */ d ; */ e ; */; select 4",
            List.of("select 3 /* a /* b /* c ; */ d ; */ e ; */", "select 4")),
        Arguments.of(
            "comment-opener-star-not-reused-for-closer",
            "select 1 /*/ ; */ ; select 2",
            List.of("select 1 /*/ ; */", "select 2")),
        Arguments.of(
            "comment-closer-slash-not-reused-for-opener",
            "select 3 /* x */*2 as v; select 4",
            List.of("select 3 /* x */*2 as v", "select 4")),
        Arguments.of(
            "comment-opener-inside-quote-or-line-comment",
            "select '/*' as d; select 6 -- /*\n; select 7",
            List.of("select '/*' as d", "select 6 -- /*", "select 7")));
  }
}
