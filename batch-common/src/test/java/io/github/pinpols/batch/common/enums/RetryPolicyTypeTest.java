package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RetryPolicyType 重试策略类型: 编码 / 展示名称声明与状态项数量锁定")
class RetryPolicyTypeTest {

  @Test
  @DisplayName("各重试策略类型的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(RetryPolicyType.NONE.code()).isEqualTo("NONE");
    assertThat(RetryPolicyType.FIXED.code()).isEqualTo("FIXED");
    assertThat(RetryPolicyType.EXPONENTIAL.code()).isEqualTo("EXPONENTIAL");
  }

  @Test
  @DisplayName("每个重试策略类型都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (RetryPolicyType type : RetryPolicyType.values()) {
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个重试策略类型的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllRetryPolicyTypes() {
    for (RetryPolicyType type : RetryPolicyType.values()) {
      assertThat(type.code()).isEqualTo(type.name());
    }
  }

  @Test
  @DisplayName("重试策略类型共三种,新增或删除需显式调整")
  void shouldContainThreeValues() {
    assertThat(RetryPolicyType.values()).hasSize(3);
  }
}
