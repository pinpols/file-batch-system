package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryPolicyTypeTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(RetryPolicyType.NONE.code()).isEqualTo("NONE");
    assertThat(RetryPolicyType.FIXED.code()).isEqualTo("FIXED");
    assertThat(RetryPolicyType.EXPONENTIAL.code()).isEqualTo("EXPONENTIAL");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (RetryPolicyType type : RetryPolicyType.values()) {
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (RetryPolicyType type : RetryPolicyType.values()) {
      assertThat(type.code()).isEqualTo(type.name());
    }
  }

  @Test
  void shouldContainThreeValues() {
    assertThat(RetryPolicyType.values()).hasSize(3);
  }
}
