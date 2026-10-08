package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("格式解析请求单测:基于暂存文件的文本读取器生命周期语义")
class FormatParseRequestTest {

  @Test
  @DisplayName("文本、二进制与 spool 工厂分别只设置对应输入载体")
  void shouldKeepInputModesSeparate() {
    Path spool = Path.of("input.csv");

    FormatParseRequest text = FormatParseRequest.fromText("a,b", null, null, false);
    FormatParseRequest binary = FormatParseRequest.fromBinary(new byte[] {1}, null, null, true);
    FormatParseRequest spooled =
        FormatParseRequest.fromSpool(null, null, false, spool, StandardCharsets.UTF_8);

    assertThat(text.payloadText()).isEqualTo("a,b");
    assertThat(text.binaryPayload()).isNull();
    assertThat(text.spoolPath()).isNull();
    assertThat(binary.payloadText()).isNull();
    assertThat(binary.binaryPayload()).containsExactly((byte) 1);
    assertThat(binary.spoolPath()).isNull();
    assertThat(spooled.payloadText()).isNull();
    assertThat(spooled.binaryPayload()).isNull();
    assertThat(spooled.spoolPath()).isEqualTo(spool);
    assertThat(spooled.spoolCharset()).isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("spool 输入必须提供文件路径")
  void shouldRejectMissingSpoolPath() {
    assertThatNullPointerException()
        .isThrownBy(() -> FormatParseRequest.fromSpool(null, null, false, null, null))
        .withMessage("spoolPath");
  }

  @Test
  @DisplayName("打开暂存文件读取器后可按行顺序读取,由调用方负责关闭")
  void shouldKeepReaderOpen_whenCallerHasNotClosedIt() throws Exception {
    Path spool = Files.createTempFile("format-parse-request-", ".csv");
    try {
      Files.writeString(spool, "header\nvalue\n", StandardCharsets.UTF_8);
      FormatParseRequest request =
          FormatParseRequest.fromSpool(null, null, false, spool, StandardCharsets.UTF_8);

      try (var reader = request.openTextReader()) {
        assertThat(reader.readLine()).isEqualTo("header");
        assertThat(reader.readLine()).isEqualTo("value");
      }
    } finally {
      Files.deleteIfExists(spool);
    }
  }
}
