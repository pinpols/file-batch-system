package com.example.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentMaskingUtilsTest {

  @Test
  void shouldReturnNullOrEmptyUnchanged() {
    assertThat(ContentMaskingUtils.maskPlainText(null)).isNull();
    assertThat(ContentMaskingUtils.maskPlainText("")).isEmpty();
  }

  @Test
  void shouldMaskDigitRunsOfFourOrMore() {
    assertThat(ContentMaskingUtils.maskPlainText("code 1234 and 123"))
        .isEqualTo("code **** and 123");
    assertThat(ContentMaskingUtils.maskPlainText("id=1234567890")).isEqualTo("id=****");
  }

  @Test
  void shouldMaskEmailLikeTokens() {
    assertThat(ContentMaskingUtils.maskPlainText("contact user@example.com please"))
        .isEqualTo("contact ***@*** please");
  }

  @Test
  void shouldApplyStrictRuleSetWhenRequested() {
    String masked = ContentMaskingUtils.maskPlainText("name: Alice and phone=1234567890", "STRICT");
    assertThat(masked).contains("name=***");
    assertThat(masked).contains("phone=***");
  }
}
