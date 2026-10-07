package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("格式解析请求单测:基于暂存文件的文本读取器生命周期语义")
class FormatParseRequestTest {

  @Test
  @DisplayName("打开暂存文件读取器后可按行顺序读取,由调用方负责关闭")
  void shouldKeepReaderOpen_whenCallerHasNotClosedIt() throws Exception {
    Path spool = Files.createTempFile("format-parse-request-", ".csv");
    try {
      Files.writeString(spool, "header\nvalue\n", StandardCharsets.UTF_8);
      FormatParseRequest request =
          new FormatParseRequest(null, null, null, null, false, spool, StandardCharsets.UTF_8);

      try (var reader = request.openTextReader()) {
        assertThat(reader.readLine()).isEqualTo("header");
        assertThat(reader.readLine()).isEqualTo("value");
      }
    } finally {
      Files.deleteIfExists(spool);
    }
  }
}
