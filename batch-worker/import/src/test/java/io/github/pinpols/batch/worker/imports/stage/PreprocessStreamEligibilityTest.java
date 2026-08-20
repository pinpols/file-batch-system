package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreprocessStreamEligibilityTest {

  @Test
  void treatsNullTemplateAsPlainTextStreamable() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(null, null)).isTrue();
  }

  @Test
  void rejectsBinaryFormats() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(payload("EXCEL"), Map.of()))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(payload("BINARY"), Map.of()))
        .isFalse();
  }

  @Test
  void rejectsConfiguredPreprocessPipeline() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("preprocess_pipeline", List.of("GUNZIP"))))
        .isFalse();
  }

  @Test
  void acceptsNoneCompressionAndEncryption() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("compress_type", "NONE", "encrypt_type", "")))
        .isTrue();
  }

  @Test
  void rejectsConfiguredCompression() {
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
