package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleAiContextSanitizerTest {

  @Test
  void sanitizesJsonValuesAndPreservesSafeContext() {
    String result = ConsoleAiContextSanitizer.sanitize(
        Map.of("jobCode", " demo\u0000job ", "attempt", 2, "tags", List.of("daily", true)), 200);

    assertThat(result).contains("demojob", "jobCode", "daily").doesNotContain("\u0000");
  }

  @Test
  void rejectsCredentialKeysEvenWhenNestedInArrays() {
    Map<String, Object> context =
        Map.of("details", List.of(List.of(Map.of("api-key", "secret-value"))));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize(context, 2000))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsOversizedSerializedContext() {
    Map<String, Object> oversizedContext = Map.of("summary", "x".repeat(40));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize(oversizedContext, 32))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsExcessivelyNestedContext() {
    Map<String, Object> context = Map.of("a", List.of(List.of(List.of(List.of(List.of("deep"))))));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize(context, 2000))
        .isInstanceOf(BizException.class);
  }
}
