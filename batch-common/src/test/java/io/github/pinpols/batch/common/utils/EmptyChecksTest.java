package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("空值判定工具: 空值, 空串与空白文本的区分及集合数组的判空语义")
class EmptyChecksTest {

  @Test
  @DisplayName("文本判定: 空值, 空串与纯空白三者语义互相区分")
  void shouldDistinguishNullFromEmptyAndBlank_whenCheckingText() {
    assertThat(EmptyChecks.isNull(null)).isTrue();
    assertThat(EmptyChecks.isNotNull("value")).isTrue();
    assertThat(EmptyChecks.isEmpty((String) null)).isTrue();
    assertThat(EmptyChecks.isEmpty("")).isTrue();
    assertThat(EmptyChecks.isEmpty(" ")).isFalse();
    assertThat(EmptyChecks.isBlank(" ")).isTrue();
    assertThat(EmptyChecks.isBlank("value")).isFalse();
  }

  @Test
  @DisplayName("集合与数组判定: 空值及零元素视为空, 含元素视为非空")
  void shouldHandleNullAndEmpty_whenCheckingCollectionsAndArrays() {
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
