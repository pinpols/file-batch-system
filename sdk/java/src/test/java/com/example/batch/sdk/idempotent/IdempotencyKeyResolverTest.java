package com.example.batch.sdk.idempotent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.sdk.task.SdkTaskContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.3 幂等键解析单测。 */
class IdempotencyKeyResolverTest {

  private static SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("tenantA", "jobX", "ti-9", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("纯字面量 → 原样返回")
  void shouldReturnLiteral_whenNoPlaceholder() {
    assertThat(IdempotencyKeyResolver.resolve("fixed-key", ctx(Map.of()))).isEqualTo("fixed-key");
  }

  @Test
  @DisplayName("上下文字段占位符 tenantId/jobCode/taskInstanceId")
  void shouldResolveContextFields() {
    String key =
        IdempotencyKeyResolver.resolve("{tenantId}:{jobCode}:{taskInstanceId}", ctx(Map.of()));
    assertThat(key).isEqualTo("tenantA:jobX:ti-9");
  }

  @Test
  @DisplayName("参数占位符 + 多占位符混合 + 字面量")
  void shouldResolveParamsAndMixedLiterals() {
    String key =
        IdempotencyKeyResolver.resolve(
            "import:{tenantId}:{bizDate}/{orderId}",
            ctx(Map.of("bizDate", "2026-06-01", "orderId", 123)));
    assertThat(key).isEqualTo("import:tenantA:2026-06-01/123");
  }

  @Test
  @DisplayName("占位符求不到值 → IllegalArgumentException")
  void shouldThrow_whenPlaceholderUnresolved() {
    assertThatThrownBy(() -> IdempotencyKeyResolver.resolve("{missing}", ctx(Map.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("{missing}");
  }

  @Test
  @DisplayName("参数值含正则特殊字符 → 安全转义不破坏替换")
  void shouldEscapeSpecialChars_inParamValue() {
    String key = IdempotencyKeyResolver.resolve("k:{p}", ctx(Map.of("p", "a$b\\c")));
    assertThat(key).isEqualTo("k:a$b\\c");
  }
}
