package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EncodingUtilsTest {

  @Test
  void normalize_shouldMapAliasesToCanonicalUtf8() {
    assertThat(EncodingUtils.normalize("utf8")).isEqualTo("UTF-8");
    assertThat(EncodingUtils.normalize(" UTF-8 ")).isEqualTo("UTF-8");
    assertThat(EncodingUtils.normalize("")).isEqualTo("UTF-8");
    assertThat(EncodingUtils.normalize(null)).isEqualTo("UTF-8");
  }

  @Test
  void normalize_shouldRejectUnknownCharset() {
    assertThatThrownBy(() -> EncodingUtils.normalize("not-a-charset"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isUtf8_shouldReturnTrueForAliasesAndBlank() {
    assertThat(EncodingUtils.isUtf8("utf-8")).isTrue();
    assertThat(EncodingUtils.isUtf8(null)).isTrue();
    assertThat(EncodingUtils.isUtf8("GBK")).isFalse();
  }

  @Test
  void stripUtf8Bom_byteArray_shouldRemoveBomWhenPresent() {
    byte[] withBom = concat(bom(), "hello".getBytes(StandardCharsets.UTF_8));

    byte[] stripped = EncodingUtils.stripUtf8Bom(withBom);

    assertThat(stripped).isNotSameAs(withBom);
    assertThat(new String(stripped, StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void stripUtf8Bom_byteArray_shouldReturnSameArrayWhenNoBom() {
    byte[] noBom = "hello".getBytes(StandardCharsets.UTF_8);

    assertThat(EncodingUtils.stripUtf8Bom(noBom)).isSameAs(noBom);
    assertThat(EncodingUtils.stripUtf8Bom(new byte[] {})).isEmpty();
  }

  @Test
  void stripUtf8Bom_stream_shouldWrapAndStrip() throws Exception {
    byte[] withBom = concat(bom(), "hello".getBytes(StandardCharsets.UTF_8));

    byte[] stripped =
        EncodingUtils.stripUtf8Bom(new ByteArrayInputStream(withBom)).readAllBytes();

    assertThat(new String(stripped, StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void gb18030AndGbk_shouldBeUsableAsCharsets() {
    String text = "客户";
    byte[] gbkBytes = text.getBytes(EncodingUtils.GBK);
    byte[] gb18030Bytes = text.getBytes(EncodingUtils.GB18030);
    assertThat(new String(gbkBytes, EncodingUtils.GB18030)).isEqualTo(text);
    assertThat(new String(gb18030Bytes, EncodingUtils.GB18030)).isEqualTo(text);
  }

  private static byte[] bom() {
    return new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = new byte[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
