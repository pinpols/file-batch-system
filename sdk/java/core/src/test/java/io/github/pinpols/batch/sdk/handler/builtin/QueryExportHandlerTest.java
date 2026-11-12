package io.github.pinpols.batch.sdk.handler.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.handler.builtin.support.DelimitedFormat;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("查询导出处理器:执行查询按表头写出分隔文件,覆盖引号转义、行数统计与目录缺失失败")
class QueryExportHandlerTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("两行结果带表头导出:含分隔符的字段加引号转义,行数计数为 2 且设置抓取批量")
  void shouldExportWithHeaderAndQuoting() throws Exception {
    final String selectFixtureRowsSql = "select id, name from t";
    Path out = tempDir.resolve("out.csv");

    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery(selectFixtureRowsSql)).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(2);
    when(meta.getColumnLabel(1)).thenReturn("id");
    when(meta.getColumnLabel(2)).thenReturn("name");
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getObject(1)).thenReturn(1, 2);
    when(rs.getObject(2)).thenReturn("x", "y,z");

    var handler =
        new QueryExportHandler(QueryExportConfig.defaults("exp", "select id, name from t"), ds);

    SdkTaskResult result = handler.execute(new SdkTaskContext(
        "t1", "job1", "ti1", 1L, "w1", Map.of("outputPath", out.toString()), Map.of()));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("rowCount", 2L);
    assertThat(Files.readAllLines(out, StandardCharsets.UTF_8))
        .containsExactly("id,name", "1,x", "2,\"y,z\"");
    verify(st).setFetchSize(anyInt());
  }

  @Test
  @DisplayName("输出目录不存在 → 失败,并提示输出目录不可用")
  void shouldFailWhenOutputDirMissing() {
    DataSource ds = mock(DataSource.class);
    var handler = new QueryExportHandler(QueryExportConfig.defaults("exp", "select 1"), ds);

    SdkTaskResult result = handler.execute(new SdkTaskContext(
        "t1", "job1", "ti1", 1L, "w1", Map.of("outputPath", "/no/such/dir/out.csv"), Map.of()));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("output directory");
  }

  @Test
  @DisplayName("关闭表头且查询无数据:只产生空文件,行数为 0 并提交事务")
  void shouldWriteEmptyFileAndCommit_whenNoHeaderAndNoRows() throws Exception {
    Path out = tempDir.resolve("empty.csv");
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery("select 1")).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(1);
    when(rs.next()).thenReturn(false);
    QueryExportConfig config = new QueryExportConfig(
        "exp", "select 1", new DelimitedFormat('|', '"', false), "outputPath", 1000);

    SdkTaskResult result = new QueryExportHandler(config, ds)
        .execute(new SdkTaskContext(
            "t1", "job1", "ti1", 1L, "w1", Map.of("outputPath", out.toString()), Map.of()));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("rowCount", 0L);
    assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEmpty();
    verify(conn).commit();
  }

  @Test
  @DisplayName("查询执行抛异常:任务失败且不提交事务")
  void shouldFailWithoutCommit_whenQueryThrows() throws Exception {
    Path out = tempDir.resolve("broken.csv");
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    Statement st = mock(Statement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery("select 1")).thenThrow(new SQLException("query broken"));

    SdkTaskResult result = new QueryExportHandler(QueryExportConfig.defaults("exp", "select 1"), ds)
        .execute(new SdkTaskContext(
            "t1", "job1", "ti1", 1L, "w1", Map.of("outputPath", out.toString()), Map.of()));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("query broken");
    verify(conn, never()).commit();
  }
}
