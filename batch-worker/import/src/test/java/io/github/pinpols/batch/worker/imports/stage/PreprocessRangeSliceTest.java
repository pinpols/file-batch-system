package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * range-slice 核心算法 {@link ImportPreprocessObjectSource#copyPartitionRange} + 资格判定 {@link
 * ImportPreprocessObjectSource#rangeSliceEligible} 单测。
 *
 * <p>核心不变式:N 个分片各自 range 输出按序拼接 == 原文件字节(等价于 无重叠 + 无遗漏 + 不劈行 + 保序)。
 */
@DisplayName("导入预处理范围切片单测:分片无损拼接不变式与切片资格判定语义")
class PreprocessRangeSliceTest {

  /** 用与 streamObjectRangeToSpool 相同的边界数学,把 data 切 N 片跑 copyPartitionRange,返回各片输出按序拼接。 */
  private static byte[] sliceAllAndConcat(byte[] data, int n) throws IOException {
    long s = data.length;
    ByteArrayOutputStream concat = new ByteArrayOutputStream();
    for (int p = 1; p <= n; p++) {
      long rawStart = s * (p - 1) / n;
      long rawEnd = p == n ? s : s * p / n;
      try (InputStream in = new ByteArrayInputStream(data)) {
        long skipped = in.skip(rawStart);
        assertThat(skipped).isEqualTo(rawStart);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportPreprocessObjectSource.copyPartitionRange(in, out, rawEnd - rawStart, p > 1);
        concat.writeBytes(out.toByteArray());
      }
    }
    return concat.toByteArray();
  }

  private static void assertLossless(String content, int... partitionCounts) throws IOException {
    byte[] data = content.getBytes(StandardCharsets.UTF_8);
    for (int n : partitionCounts) {
      assertThat(new String(sliceAllAndConcat(data, n), StandardCharsets.UTF_8))
          .as("N=%d 分片拼接应无损还原原文", n)
          .isEqualTo(content);
    }
  }

  @Test
  @DisplayName("定长行按多个分片切分后拼接无损,行不重不漏")
  void shouldSliceLosslessly_whenFixedWidthLines() throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      sb.append(String.format("%05d", i)).append("ABCDEFGHIJ").append('\n');
    }
    assertLossless(sb.toString(), 1, 2, 3, 4, 5, 7, 13);
  }

  @Test
  @DisplayName("末行没有换行符时,分片拼接仍与原内容一致")
  void shouldSliceLosslessly_whenLastLineHasNoTrailingNewline() throws IOException {
    assertLossless("aaa\nbbb\nccc\nddd\neee", 1, 2, 3, 4);
  }

  @Test
  @DisplayName("存在空行时,分片拼接不丢失也不重复空行")
  void shouldSliceLosslessly_whenEmptyLinesPresent() throws IOException {
    assertLossless("a\n\nb\n\n\nc\n", 1, 2, 3, 4);
  }

  @Test
  @DisplayName("回车换行行尾下,分片边界不劈开行且拼接无损")
  void shouldSliceLosslessly_whenCrlfLineEndings() throws IOException {
    assertLossless("row1\r\nrow2\r\nrow3\r\nrow4\r\n", 1, 2, 3, 5);
  }

  @Test
  @DisplayName("超长行跨越多个分片时,只归属首个字节所在分片")
  void shouldAssignLongLineToSinglePartition_whenLineSpansPartitions() throws IOException {
    // 一条超长行远大于单片 sliceLen:仍只被首字节所属分片拥有,无重复无遗漏
    assertLossless("x".repeat(2000) + "\n" + "y".repeat(10) + "\n", 8);
  }

  @Test
  @DisplayName("四个分片顺序拼接后,与原始内容逐字节一致")
  void shouldConcatenatePartitionsWithoutDuplicateOrGap() throws IOException {
    String content = "L0\nL1\nL2\nL3\nL4\nL5\nL6\nL7\nL8\nL9\n";
    byte[] data = content.getBytes(StandardCharsets.UTF_8);
    // 逐片收集行,断言所有行恰好出现一次、顺序完整
    int n = 4;
    long s = data.length;
    StringBuilder all = new StringBuilder();
    for (int p = 1; p <= n; p++) {
      long rawStart = s * (p - 1) / n;
      long rawEnd = p == n ? s : s * p / n;
      try (InputStream in = new ByteArrayInputStream(data)) {
        in.skip(rawStart);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportPreprocessObjectSource.copyPartitionRange(in, out, rawEnd - rawStart, p > 1);
        all.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
    }
    assertThat(all).hasToString(content);
  }

  // ---- 资格判定 gate ----

  private static Map<String, Object> tc(String format) {
    return Map.of("file_format_type", format);
  }

  @Test
  @DisplayName("定长格式,多分片且单字节编码时判定为可切片")
  void eligible_fixedWidthUtf8MultiPartition() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.UTF_8))
        .isTrue();
  }

  @Test
  @DisplayName("分隔符格式未显式开启时不判定为可切片")
  void notEligible_delimitedWithoutOptIn() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("DELIMITED"), 1, 4, StandardCharsets.UTF_8))
        .isFalse();
  }

  @Test
  @DisplayName("分隔符格式显式开启切片后判定为可切片")
  void eligible_delimitedWithOptIn() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null,
            Map.of("file_format_type", "DELIMITED", "partition_range_slice", "true"),
            3,
            4,
            StandardCharsets.UTF_8))
        .isTrue();
  }

  @Test
  @DisplayName("结构化与表格格式一律不判定为可切片")
  void notEligible_jsonXmlExcel() {
    for (String fmt : new String[] {"JSON", "XML", "EXCEL"}) {
      assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
              null, tc(fmt), 2, 4, StandardCharsets.UTF_8))
          .as(fmt)
          .isFalse();
    }
  }

  @Test
  @DisplayName("单片,分片序号越界或缺失时都不判定为可切片")
  void notEligible_singlePartitionOrBadIndex() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 1, 1, StandardCharsets.UTF_8))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 5, 4, StandardCharsets.UTF_8))
        .isFalse();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), null, 4, StandardCharsets.UTF_8))
        .isFalse();
  }

  @Test
  @DisplayName("多字节编码下换行不可安全切分,不判定为可切片")
  void notEligible_newlineUnsafeCharset() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.UTF_16))
        .isFalse();
  }

  @Test
  @DisplayName("单字节编码族都被视为换行安全,可切片")
  void eligible_asciiAndLatin1AreNewlineSafe() {
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.US_ASCII))
        .isTrue();
    assertThat(ImportPreprocessObjectSource.rangeSliceEligible(
            null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.ISO_8859_1))
        .isTrue();
  }
}
