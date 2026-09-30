package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.contract.request.auth.AiPageContextRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleAiContextSanitizerTest {

  @Test
  void sanitizesJsonValuesAndPreservesSafeContext() {
    AiPageContextRequest context = new AiPageContextRequest();
    context.setPageType(" job\u0000detail ");
    context.setObjectType("jobInstance");
    context.setObjectId("demo-1");
    String result = ConsoleAiContextSanitizer.sanitize("v1", Map.of(), context, 200);

    assertThat(result).contains("jobdetail", "objectType", "demo-1").doesNotContain("\u0000");
  }

  @Test
  void rejectsContextFieldsOutsideTheVersionedAllowlist() {
    Map<String, Object> context = Map.of("diagnostic", "contains sensitive details");

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", context, null, 2000))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsOversizedSerializedContext() {
    Map<String, Object> oversizedContext = Map.of("pageType", "x".repeat(40));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", oversizedContext, null, 32))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsExcessivelyNestedContext() {
    Map<String, Object> context = Map.of("objectId", List.of("nested"));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", context, null, 2000))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsUnsupportedContextVersions() {
    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v2", Map.of(), null, 2000))
        .isInstanceOf(BizException.class);
  }
}
