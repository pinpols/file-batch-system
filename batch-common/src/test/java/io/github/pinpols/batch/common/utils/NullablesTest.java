package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("空值回退工具: 首选值非空时取首选值, 否则原样返回可为空的回退值")
class NullablesTest {

  @Test
  @DisplayName("首选值非空时返回首选值, 不看回退值")
  void shouldReturnPreferred_whenPreferredIsNotNull() {
    assertThat(Nullables.coalesce("a", "b")).isEqualTo("a");
  }

  @Test
  @DisplayName("首选值为空时返回回退值")
  void shouldReturnFallback_whenPreferredIsNull() {
    assertThat(Nullables.coalesce(null, "b")).isEqualTo("b");
  }

  @Test
  @DisplayName("两边都为空时返回空, 不抛异常")
  void shouldReturnNull_whenBothAreNull() {
    assertThat(Nullables.<String>coalesce(null, null)).isNull();
  }
}
