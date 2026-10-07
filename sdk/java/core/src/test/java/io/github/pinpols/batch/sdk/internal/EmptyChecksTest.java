package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EmptyChecks — 空值与非空值判定")
class EmptyChecksTest {

  @Test
  @DisplayName("空与非空判定互为正反面,结果与入参一致")
  void shouldDistinguishNullFromNonNull_whenChecking() {
    assertThat(EmptyChecks.isNull(null)).isTrue();
    assertThat(EmptyChecks.isNull("value")).isFalse();
    assertThat(EmptyChecks.isNotNull("value")).isTrue();
    assertThat(EmptyChecks.isNotNull(null)).isFalse();
  }
}
