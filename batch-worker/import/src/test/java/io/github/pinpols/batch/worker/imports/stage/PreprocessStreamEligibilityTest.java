package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入预处理流式直载资格单测:格式,管道与压缩加密配置的准入判定")
class PreprocessStreamEligibilityTest {

  @Test
  @DisplayName("模板缺失时按纯文本处理,允许直接流式装载")
  void shouldAllowDirectStreaming_whenTemplateMissing() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(null, null)).isTrue();
  }

  @Test
  @DisplayName("二进制与表格格式不允许直接流式装载")
  void shouldRejectDirectStreaming_whenFormatIsBinary() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(payload("EXCEL"), Map.of()))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(payload("BINARY"), Map.of()))
        .isFalse();
  }

  @Test
  @DisplayName("配置了非空预处理管道时不允许直接流式装载")
  void shouldRejectDirectStreaming_whenPreprocessPipelineConfigured() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("preprocess_pipeline", List.of("GUNZIP"))))
        .isFalse();
  }

  @Test
  @DisplayName("压缩与加密都为空配置时允许直接流式装载")
  void shouldAllowDirectStreaming_whenCompressionAndEncryptionAreNone() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("compress_type", "NONE", "encrypt_type", "")))
        .isTrue();
  }

  @Test
  @DisplayName("配置了压缩算法时不允许直接流式装载")
  void shouldRejectDirectStreaming_whenCompressionConfigured() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("compress_type", "GZIP")))
        .isFalse();
  }

  private static ImportPayload payload(String format) {
    return new ImportPayload(
        null,
        null,
        null,
        null,
        format,
        null,
        null,
        null,
        null,
        null,
        null,
        "S3",
        "objects/input.csv",
        "batch-test",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }
}
