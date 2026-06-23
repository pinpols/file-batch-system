package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdsTest {

  @Test
  void normalize_trimsAndKeepsSafeCharacters() {
    assertThat(CorrelationIds.normalize("  trace-1_A.b:c  ")).isEqualTo("trace-1_A.b:c");
  }

  @Test
  void normalize_replacesUnsafeCharactersAndControls() {
    assertThat(CorrelationIds.normalize("trace 1/\nnext")).isEqualTo("trace_1_next");
  }

  @Test
  void normalize_capsAtHeaderColumnLength() {
    assertThat(CorrelationIds.normalize("x".repeat(200))).hasSize(128);
  }

  @Test
  void normalize_usesFallbackWhenInputBlankOrInvalid() {
    assertThat(CorrelationIds.normalize(" \n\t ", "fallback")).isEqualTo("fallback");
  }
}
