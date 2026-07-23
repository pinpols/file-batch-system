package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuotaExceededStrategyTest {

  @Test
  void validCode_shouldResolve() {
    assertThat(QuotaExceededStrategy.from(" QUEUE_DEFER "))
        .isEqualTo(QuotaExceededStrategy.QUEUE_DEFER);
  }

  @Test
  void unknownCode_shouldFailInsteadOfFallingBackToReject() {
    assertThatThrownBy(() -> QuotaExceededStrategy.from("QUEU_DEFER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUEU_DEFER");
  }

  @Test
  void blankCode_shouldFailInsteadOfFallingBackToReject() {
    assertThatThrownBy(() -> QuotaExceededStrategy.from(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }
}
