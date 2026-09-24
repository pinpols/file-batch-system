package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmptyChecksTest {

  @Test
  void distinguishesNullAndNonNullValues() {
    assertThat(EmptyChecks.isNull(null)).isTrue();
    assertThat(EmptyChecks.isNull("value")).isFalse();
    assertThat(EmptyChecks.isNotNull("value")).isTrue();
    assertThat(EmptyChecks.isNotNull(null)).isFalse();
  }
}
