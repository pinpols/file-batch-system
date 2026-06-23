package io.github.pinpols.batch.sdk.handler.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileImportHandlerTest {

  @TempDir Path tempDir;

  private SdkTaskContext ctx(Path file) {
    return new SdkTaskContext(
        "t1", "job1", "ti1", 1L, "w1", Map.of("filePath", file.toString()), Map.of());
  }

  @Test
  void shouldImportRowsSkippingHeaderAndCommit() throws Exception {
    Path csv = tempDir.resolve("in.csv");
    Files.writeString(csv, "a,b\n1,x\n2,\"y,z\"\n", StandardCharsets.UTF_8);

    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(any(String.class))).thenReturn(ps);

    var handler =
        new FileImportHandler(FileImportConfig.defaults("imp", "my_table", List.of("a", "b")), ds);

    SdkTaskResult result = handler.execute(ctx(csv));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("success", 2L).containsEntry("total", 2L);
    verify(ps, times(2)).addBatch();
    verify(ps, atLeastOnce()).executeBatch();
    verify(conn).commit();
    // 2 行 × 2 列 = 4 次绑定;第二行第二字段去引号还原为 y,z
    verify(ps, times(4)).setObject(anyInt(), any());
  }

  @Test
  void shouldFailWhenColumnCountMismatch() throws Exception {
    Path csv = tempDir.resolve("bad.csv");
    Files.writeString(csv, "a,b\n1\n", StandardCharsets.UTF_8);

    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(any(String.class))).thenReturn(ps);

    var handler =
        new FileImportHandler(FileImportConfig.defaults("imp", "my_table", List.of("a", "b")), ds);

    SdkTaskResult result = handler.execute(ctx(csv));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("line 2");
  }

  @Test
  void shouldFailWhenFilePathMissing() {
    DataSource ds = mock(DataSource.class);
    var handler =
        new FileImportHandler(FileImportConfig.defaults("imp", "my_table", List.of("a", "b")), ds);

    SdkTaskResult result =
        handler.execute(new SdkTaskContext("t1", "job1", "ti1", 1L, "w1", Map.of(), Map.of()));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("filePath");
  }
}
