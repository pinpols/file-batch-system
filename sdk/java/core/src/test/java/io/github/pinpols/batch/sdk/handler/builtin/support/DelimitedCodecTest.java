package io.github.pinpols.batch.sdk.handler.builtin.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分隔文本编解码:切分与转义规则,覆盖引号、转义、空尾字段与自定义分隔符")
class DelimitedCodecTest {

  @Test
  @DisplayName("普通字段按分隔符切分:三列原样解析")
  void shouldParsePlainFields() {
    assertThat(DelimitedCodec.parse("a,b,c", ',', '"')).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("引号包裹的字段内含分隔符:不按分隔符切分,按原值整段返回")
  void shouldParseQuotedFieldContainingDelimiter() {
    assertThat(DelimitedCodec.parse("1,\"x,y\",z", ',', '"')).containsExactly("1", "x,y", "z");
  }

  @Test
  @DisplayName("引号字段内的成对引号还原为一个引号:转义解析结果与原值一致")
  void shouldParseEscapedQuoteInsideQuotedField() {
    assertThat(DelimitedCodec.parse("\"he said \"\"hi\"\"\",b", ',', '"'))
        .containsExactly("he said \"hi\"", "b");
  }

  @Test
  @DisplayName("行尾以分隔符结束时补一个空字段:解析结果末位为空串")
  void shouldYieldEmptyTrailingField() {
    assertThat(DelimitedCodec.parse("a,b,", ',', '"')).containsExactly("a", "b", "");
  }

  @Test
  @DisplayName("编码时含分隔符或引号的字段自动加引号:内层引号成对转义")
  void shouldEncodeQuotingWhenNeeded() {
    String line = DelimitedCodec.encode(List.of("1", "x,y", "he \"q\""), ',', '"');
    assertThat(line).isEqualTo("1,\"x,y\",\"he \"\"q\"\"\"");
  }

  @Test
  @DisplayName("先编码再解析:字段顺序与内容同原始输入完全一致")
  void shouldRoundTrip() {
    List<String> fields = List.of("plain", "with,comma", "with\"quote", "");
    String encoded = DelimitedCodec.encode(fields, ',', '"');
    assertThat(DelimitedCodec.parse(encoded, ',', '"')).containsExactlyElementsOf(fields);
  }

  @Test
  @DisplayName("指定竖线为分隔符:按该符号切分出三列")
  void shouldSupportCustomDelimiter() {
    assertThat(DelimitedCodec.parse("a|b|c", '|', '"')).containsExactly("a", "b", "c");
  }
}
