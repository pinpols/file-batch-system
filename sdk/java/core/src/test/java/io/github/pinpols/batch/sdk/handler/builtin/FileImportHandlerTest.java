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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("文件导入处理器:按表头解析分隔文件并分批绑定入库,覆盖成功、列数不符与参数缺失")
class FileImportHandlerTest {

  @TempDir
  Path tempDir;

  private SdkTaskContext ctx(Path file) {
    return new SdkTaskContext(
        "t1", "job1", "ti1", 1L, "w1", Map.of("filePath", file.toString()), Map.of());
  }

  @Test
  @DisplayName("跳过表头导入两行:每行各绑定一次后统一提交,成功计数与总数均为 2")
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
  @DisplayName("第二行列数与表头不一致 → 整体失败,并提示出错所在行")
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
  @DisplayName("缺少文件路径参数 → 直接失败,并提示缺失的参数名")
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
