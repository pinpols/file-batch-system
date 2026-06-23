package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * range-slice 核心算法 {@link PreprocessStep#copyPartitionRange} + 资格判定 {@link
 * PreprocessStep#rangeSliceEligible} 单测。
 *
 * <p>核心不变式:N 个分片各自 range 输出按序拼接 == 原文件字节(等价于 无重叠 + 无遗漏 + 不劈行 + 保序)。
 */
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
        PreprocessStep.copyPartitionRange(in, out, rawEnd - rawStart, p > 1);
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
  void losslessForFixedWidthLines() throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      sb.append(String.format("%05d", i)).append("ABCDEFGHIJ").append('\n');
    }
    assertLossless(sb.toString(), 1, 2, 3, 4, 5, 7, 13);
  }

  @Test
  void losslessWhenLastLineHasNoTrailingNewline() throws IOException {
    assertLossless("aaa\nbbb\nccc\nddd\neee", 1, 2, 3, 4);
  }

  @Test
  void losslessWithEmptyLines() throws IOException {
    assertLossless("a\n\nb\n\n\nc\n", 1, 2, 3, 4);
  }

  @Test
  void losslessWithCrlf() throws IOException {
    assertLossless("row1\r\nrow2\r\nrow3\r\nrow4\r\n", 1, 2, 3, 5);
  }

  @Test
  void losslessWhenSingleLineSpansMultiplePartitions() throws IOException {
    // 一条超长行远大于单片 sliceLen:仍只被首字节所属分片拥有,无重复无遗漏
    assertLossless("x".repeat(2000) + "\n" + "y".repeat(10) + "\n", 8);
  }

  @Test
  void noDuplicateOrGapAcrossPartitions() throws IOException {
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
        PreprocessStep.copyPartitionRange(in, out, rawEnd - rawStart, p > 1);
        all.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
    }
    assertThat(all.toString()).isEqualTo(content);
  }

  // ---- 资格判定 gate ----

  private static Map<String, Object> tc(String format) {
    return Map.of("file_format_type", format);
  }

  @Test
  void eligible_fixedWidthUtf8MultiPartition() {
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.UTF_8))
        .isTrue();
  }

  @Test
  void notEligible_delimitedWithoutOptIn() {
    assertThat(
            PreprocessStep.rangeSliceEligible(null, tc("DELIMITED"), 1, 4, StandardCharsets.UTF_8))
        .isFalse();
  }

  @Test
  void eligible_delimitedWithOptIn() {
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null,
                Map.of("file_format_type", "DELIMITED", "partition_range_slice", "true"),
                3,
                4,
                StandardCharsets.UTF_8))
        .isTrue();
  }

  @Test
  void notEligible_jsonXmlExcel() {
    for (String fmt : new String[] {"JSON", "XML", "EXCEL"}) {
      assertThat(PreprocessStep.rangeSliceEligible(null, tc(fmt), 2, 4, StandardCharsets.UTF_8))
          .as(fmt)
          .isFalse();
    }
  }

  @Test
  void notEligible_singlePartitionOrBadIndex() {
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 1, 1, StandardCharsets.UTF_8))
        .isFalse();
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 5, 4, StandardCharsets.UTF_8))
        .isFalse();
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), null, 4, StandardCharsets.UTF_8))
        .isFalse();
  }

  @Test
  void notEligible_newlineUnsafeCharset() {
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.UTF_16))
        .isFalse();
  }

  @Test
  void eligible_asciiAndLatin1AreNewlineSafe() {
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.US_ASCII))
        .isTrue();
    assertThat(
            PreprocessStep.rangeSliceEligible(
                null, tc("FIXED_WIDTH"), 2, 4, StandardCharsets.ISO_8859_1))
        .isTrue();
  }
}
