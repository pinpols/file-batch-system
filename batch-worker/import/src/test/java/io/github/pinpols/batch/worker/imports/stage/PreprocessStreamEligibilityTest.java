package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreprocessStreamEligibilityTest {

  @Test
  void treatsNullTemplateAsPlainTextStreamable() {
    assertThat(PreprocessStep.canStreamObjectDirect(null, null)).isTrue();
  }

  @Test
  void rejectsBinaryFormats() {
    assertThat(PreprocessStep.canStreamObjectDirect(payload("EXCEL"), Map.of())).isFalse();
    assertThat(PreprocessStep.canStreamObjectDirect(payload("BINARY"), Map.of()))
        .isFalse();
  }

  @Test
  void rejectsConfiguredPreprocessPipeline() {
    assertThat(PreprocessStep.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("preprocess_pipeline", List.of("GUNZIP"))))
        .isFalse();
  }

  @Test
  void acceptsNoneCompressionAndEncryption() {
    assertThat(PreprocessStep.canStreamObjectDirect(
            payload("DELIMITED"), Map.of("compress_type", "NONE", "encrypt_type", "")))
        .isTrue();
  }

  @Test
  void rejectsConfiguredCompression() {
    assertThat(PreprocessStep.canStreamObjectDirect(
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
