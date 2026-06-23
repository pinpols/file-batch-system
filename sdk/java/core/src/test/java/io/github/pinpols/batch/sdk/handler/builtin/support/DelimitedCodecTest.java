package io.github.pinpols.batch.sdk.handler.builtin.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DelimitedCodecTest {

  @Test
  void shouldParsePlainFields() {
    assertThat(DelimitedCodec.parse("a,b,c", ',', '"')).containsExactly("a", "b", "c");
  }

  @Test
  void shouldParseQuotedFieldContainingDelimiter() {
    assertThat(DelimitedCodec.parse("1,\"x,y\",z", ',', '"')).containsExactly("1", "x,y", "z");
  }

  @Test
  void shouldParseEscapedQuoteInsideQuotedField() {
    assertThat(DelimitedCodec.parse("\"he said \"\"hi\"\"\",b", ',', '"'))
        .containsExactly("he said \"hi\"", "b");
  }

  @Test
  void shouldYieldEmptyTrailingField() {
    assertThat(DelimitedCodec.parse("a,b,", ',', '"')).containsExactly("a", "b", "");
  }

  @Test
  void shouldEncodeQuotingWhenNeeded() {
    String line = DelimitedCodec.encode(List.of("1", "x,y", "he \"q\""), ',', '"');
    assertThat(line).isEqualTo("1,\"x,y\",\"he \"\"q\"\"\"");
  }

  @Test
  void shouldRoundTrip() {
    List<String> fields = List.of("plain", "with,comma", "with\"quote", "");
    String encoded = DelimitedCodec.encode(fields, ',', '"');
    assertThat(DelimitedCodec.parse(encoded, ',', '"')).containsExactlyElementsOf(fields);
  }

  @Test
  void shouldSupportCustomDelimiter() {
    assertThat(DelimitedCodec.parse("a|b|c", '|', '"')).containsExactly("a", "b", "c");
  }
}
