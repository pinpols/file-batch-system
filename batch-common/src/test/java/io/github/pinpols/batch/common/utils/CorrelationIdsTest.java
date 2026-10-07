package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("链路标识工具: 字符归一, 长度上限与空白输入的兜底取值")
class CorrelationIdsTest {

  @Test
  @DisplayName("归一化: 去除首尾空白并保留安全字符")
  void normalize_trimsAndKeepsSafeCharacters() {
    assertThat(CorrelationIds.normalize("  trace-1_A.b:c  ")).isEqualTo("trace-1_A.b:c");
  }

  @Test
  @DisplayName("归一化: 空格, 斜杠与换行等不安全字符统一替换为下划线")
  void normalize_replacesUnsafeCharactersAndControls() {
    assertThat(CorrelationIds.normalize("trace 1/\nnext")).isEqualTo("trace_1_next");
  }

  @Test
  @DisplayName("归一化: 超长输入截断到列长上限")
  void normalize_capsAtHeaderColumnLength() {
    assertThat(CorrelationIds.normalize("x".repeat(200))).hasSize(128);
  }

  @Test
  @DisplayName("归一化: 空白输入返回调用方提供的兜底值")
  void normalize_usesFallbackWhenInputBlankOrInvalid() {
    assertThat(CorrelationIds.normalize(" \n\t ", "fallback")).isEqualTo("fallback");
  }
}
