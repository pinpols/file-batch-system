package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

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
}
