package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmptyChecksTest {

  @Test
  void distinguishesNullEmptyAndBlank() {
    assertThat(EmptyChecks.isNull(null)).isTrue();
    assertThat(EmptyChecks.isNotNull("value")).isTrue();
    assertThat(EmptyChecks.isEmpty((String) null)).isTrue();
    assertThat(EmptyChecks.isEmpty("")).isTrue();
    assertThat(EmptyChecks.isEmpty(" ")).isFalse();
    assertThat(EmptyChecks.isBlank(" ")).isTrue();
    assertThat(EmptyChecks.isBlank("value")).isFalse();
  }

  @Test
  void handlesNullAndEmptyCollections() {
    assertThat(EmptyChecks.isEmpty((List<?>) null)).isTrue();
    assertThat(EmptyChecks.isEmpty(List.of())).isTrue();
    assertThat(EmptyChecks.isEmpty(List.of("value"))).isFalse();
    assertThat(EmptyChecks.isEmpty((Map<?, ?>) null)).isTrue();
    assertThat(EmptyChecks.isEmpty(Map.of())).isTrue();
    assertThat(EmptyChecks.isEmpty(Map.of("key", "value"))).isFalse();
    assertThat(EmptyChecks.isEmpty((Object[]) null)).isTrue();
    assertThat(EmptyChecks.isEmpty(new Object[0])).isTrue();
    assertThat(EmptyChecks.isNotEmpty(new Object[] {"value"})).isTrue();
  }
}
