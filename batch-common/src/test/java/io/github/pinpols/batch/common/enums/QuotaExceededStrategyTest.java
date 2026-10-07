package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QuotaExceededStrategy 配额超限策略: 编码去空白解析与非法编码的快速失败")
class QuotaExceededStrategyTest {

  @Test
  @DisplayName("带前后空白的合法策略编码仍能解析为排队等待策略")
  void validCode_shouldResolve() {
    assertThat(QuotaExceededStrategy.from(" QUEUE_DEFER "))
        .isEqualTo(QuotaExceededStrategy.QUEUE_DEFER);
  }

  @Test
  @DisplayName("拼写错误的策略编码直接抛参数异常,不静默回退为拒绝")
  void unknownCode_shouldFailInsteadOfFallingBackToReject() {
    assertThatThrownBy(() -> QuotaExceededStrategy.from("QUEU_DEFER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUEU_DEFER");
  }

  @Test
  @DisplayName("空白策略编码直接抛参数异常,不静默回退为拒绝")
  void blankCode_shouldFailInsteadOfFallingBackToReject() {
    assertThatThrownBy(() -> QuotaExceededStrategy.from(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }
}
