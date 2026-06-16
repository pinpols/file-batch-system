package com.example.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeBasedPartitionCountResolverTest {

  private final RuntimeBasedPartitionCountResolver resolver =
      new RuntimeBasedPartitionCountResolver();

  @Test
  void shouldCeilDivideNormally() {
    int result =
        resolver.resolve(
            null,
            Map.of("historicalDurationSeconds", 1000L, "targetPartitionDurationSeconds", 300),
            null);
    // ceil(1000 / 300) = 4
    assertThat(result).isEqualTo(4);
  }

  @Test
  void shouldReturnZeroWhenParamsMissingOrNonPositive() {
    assertThat(resolver.resolve(null, Map.of(), null)).isZero();
    assertThat(resolver.resolve(null, Map.of("historicalDurationSeconds", 1000L), null)).isZero();
  }

  @Test
  void shouldNotOverflowToNegativeNearLongMax() {
    // 旧 (dividend + divisor - 1) 写法在此处溢出为负 → (int) 截断成负数 / 退化为 1。
    // 溢出安全写法应得到正确的大正整数(此处封顶 Integer.MAX_VALUE)。
    int result =
        resolver.resolve(
            null,
            Map.of(
                "historicalDurationSeconds", Long.MAX_VALUE, "targetPartitionDurationSeconds", 1),
            null);
    assertThat(result).isPositive().isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void shouldCapAtIntegerMaxValue() {
    int result =
        resolver.resolve(
            null,
            Map.of(
                "historicalDurationSeconds", Long.MAX_VALUE, "targetPartitionDurationSeconds", 2),
            null);
    // ceil(Long.MAX/2) 仍远超 Integer.MAX → 封顶。
    assertThat(result).isEqualTo(Integer.MAX_VALUE);
  }
}
