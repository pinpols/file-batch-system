package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入预处理对象源单测:直载与范围切分的适用条件及整行边界语义")
class ImportPreprocessObjectSourceTest {

  @Test
  @DisplayName("二进制格式不允许直接从对象流式装载")
  void shouldRejectDirectStreaming_whenFormatIsBinary() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "EXCEL")))
        .isFalse();
  }

  @Test
  @DisplayName("配置了非空预处理管道时不允许直载,空管道列表则允许")
  void shouldRejectDirectStreaming_whenPreprocessPipelineConfigured() {
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "DELIMITED", "preprocess_pipeline", "TRIM")))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.canStreamObjectDirect(
            null, Map.of("file_format_type", "DELIMITED", "preprocess_pipeline", "[]")))
        .isTrue();
  }

  @Test
  @DisplayName("范围切片仅在格式安全,多分片且单字节编码时可用,否则拒绝")
  void shouldAllowRangeSlicing_whenFormatPartitionAndCharsetAreSafe() {
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
  @DisplayName("范围终点落在记录中间时,只拷贝完整的整行")
  void shouldCopyCompleteRecords_whenRangeEndsMidRecord() throws Exception {
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
