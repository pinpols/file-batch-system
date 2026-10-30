package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("纯文本脱敏工具: 边界输入, 数字与邮箱规则及严格规则集命中")
class ContentMaskingUtilsTest {

  @Test
  @DisplayName("空值与空串: 脱敏结果原样返回")
  void shouldReturnNullOrEmptyUnchanged() {
    assertThat(ContentMaskingUtils.maskPlainText(null)).isNull();
    assertThat(ContentMaskingUtils.maskPlainText("")).isEmpty();
  }

  @Test
  @DisplayName("连续四位及以上数字: 整段替换为掩码, 不足四位原样保留")
  void shouldMaskDigitRunsOfFourOrMore() {
    assertThat(ContentMaskingUtils.maskPlainText("code 1234 and 123"))
        .isEqualTo("code **** and 123");
    assertThat(ContentMaskingUtils.maskPlainText("id=1234567890")).isEqualTo("id=****");
  }

  @Test
  @DisplayName("邮箱样式片段: 账号与域名部分分别掩码")
  void shouldMaskEmailLikeTokens() {
    assertThat(ContentMaskingUtils.maskPlainText("contact user@example.com please"))
        .isEqualTo("contact ***@*** please");
  }

  @Test
  @DisplayName("指定严格规则集: 姓名与手机号字段均命中并掩码")
  void shouldApplyStrictRuleSetWhenRequested() {
    String masked = ContentMaskingUtils.maskPlainText("name: Alice and phone=1234567890", "STRICT");
    assertThat(masked).contains("name=***").contains("phone=***");
  }

  @Test
  @DisplayName("严格规则遮蔽 JSON 的姓名和地址且保留有效结构")
  void shouldMaskNamedFieldsInJsonContent() {
    String input =
        "{\"name\":\"Alice Smith\",\"nested\":[{\"address\":\"Main Street\",\"count\":12,\"id\":12345678}]}";

    String masked = ContentMaskingUtils.maskPlainText(input, "STRICT");

    assertThat(masked).doesNotContain("Alice Smith", "Main Street");
    JsonNode result = JsonUtils.fromJson(masked, JsonNode.class);
    assertThat(result.path("name").asText()).isEqualTo("***");
    assertThat(result.path("nested").get(0).path("address").asText()).isEqualTo("***");
    assertThat(result.path("nested").get(0).path("count").asInt()).isEqualTo(12);
    assertThat(result.path("nested").get(0).path("id").asText()).isEqualTo("****");
  }

  @Test
  @DisplayName("未启用严格规则时 JSON 的普通姓名保持，基线数字仍脱敏")
  void shouldRetainJsonNamesWhenStrictRulesAreDisabled() {
    String masked = ContentMaskingUtils.maskPlainText("{\"name\":\"Alice\",\"id\":123456}");
    JsonNode result = JsonUtils.fromJson(masked, JsonNode.class);
    assertThat(result.path("name").asText()).isEqualTo("Alice");
    assertThat(result.path("id").asText()).isEqualTo("****");
  }

  @Test
  @DisplayName("非完整 JSON 仍使用文本规则")
  void shouldApplyTextRulesToMalformedJsonLikeInput() {
    assertThat(ContentMaskingUtils.maskPlainText("{name=Alice", "STRICT")).isEqualTo("{name=***");
  }

  @Test
  @DisplayName("JSON 字符串内嵌的结构化原始行也遵循严格脱敏")
  void shouldMaskJsonEncodedInsideStrings() {
    String inner = "{\"name\":\"Alice Smith\",\"count\":12}";
    String encoded = JsonUtils.toJson(inner);
    String nested = "{\"payload\":" + encoded + "}";

    String maskedString = ContentMaskingUtils.maskPlainText(encoded, "STRICT");
    String maskedNested = ContentMaskingUtils.maskPlainText(nested, "STRICT");

    assertThat(maskedString).doesNotContain("Alice Smith");
    assertThat(maskedNested).doesNotContain("Alice Smith");
    assertThat(JsonUtils.fromJson(maskedString, JsonNode.class).isTextual()).isTrue();
    assertThat(JsonUtils.fromJson(maskedNested, JsonNode.class).path("payload").isTextual())
        .isTrue();
  }

  @Test
  @DisplayName("过深 JSON 子树安全收口并保留可解析结构")
  void shouldMaskOverlyDeepJsonSubtrees() {
    String input = "[".repeat(70) + "\"Alice\"" + "]".repeat(70);
    String masked = ContentMaskingUtils.maskPlainText(input, "STRICT");

    assertThat(masked).doesNotContain("Alice");
    assertThat(JsonUtils.fromJson(masked, JsonNode.class).isArray()).isTrue();
  }

  @Test
  @DisplayName("JSON 解析深度超限时不回显原始敏感内容")
  void shouldNotExposeJsonWhenParserDepthIsExceeded() {
    String input = "[".repeat(1100) + "{\"name\":\"Alice\"}" + "]".repeat(1100);

    assertThatThrownBy(() -> JsonUtils.fromJson(input, JsonNode.class))
        .hasCauseInstanceOf(StreamConstraintsException.class);
    String masked = ContentMaskingUtils.maskPlainText(input, "STRICT");
    assertThat(masked).doesNotContain("Alice");
    assertThat(JsonUtils.fromJson(masked, JsonNode.class).asText()).isEqualTo("***");
    String encoded = JsonUtils.toJson(input);
    String maskedEncoded = ContentMaskingUtils.maskPlainText(encoded, "STRICT");
    assertThat(maskedEncoded).doesNotContain("Alice");
    assertThat(JsonUtils.fromJson(maskedEncoded, JsonNode.class).isTextual()).isTrue();
  }
}
