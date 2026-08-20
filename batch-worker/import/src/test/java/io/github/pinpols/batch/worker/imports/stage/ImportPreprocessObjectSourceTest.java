package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportPreprocessObjectSourceTest {

  @Test
  void rejectsDirectStreamingForBinaryFormats() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "EXCEL")))
        .isFalse();
  }

  @Test
  void rejectsDirectStreamingWhenPreprocessPipelineIsConfigured() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "DELIMITED", "preprocess_pipeline", "TRIM")))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "DELIMITED", "preprocess_pipeline", "[]")))
        .isTrue();
  }

  @Test
  void allowsRangeSlicingOnlyForSafeFormatPartitionAndCharset() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, Map.of("file_format_type", "FIXED_WIDTH"), 1, 2, StandardCharsets.UTF_8))
        .isTrue();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, Map.of("file_format_type", "DELIMITED"), 1, 2, StandardCharsets.UTF_8))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null,
            Map.of("file_format_type", "DELIMITED", "partition_range_slice", true),
            1,
            2,
            StandardCharsets.UTF_8))
        .isTrue();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, Map.of("file_format_type", "FIXED_WIDTH"), 1, 2, StandardCharsets.UTF_16))
        .isFalse();
  }

  @Test
  void copiesCompleteRecordsWhenRangeEndsInTheMiddleOfRecord() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    long written = ImportPreprocessObjectSource.copyPartitionRange(
        new ByteArrayInputStream("first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8)),
        output,
        8,
        false);

    assertThat(written).isEqualTo("first\nsecond\n".getBytes(StandardCharsets.UTF_8).length);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("first\nsecond\n");
  }
}
