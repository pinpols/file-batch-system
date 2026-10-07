package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.contract.request.auth.AiPageContextRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 上下文清洗:字段白名单、长度与嵌套深度校验")
class ConsoleAiContextSanitizerTest {

  @Test
  @DisplayName("上下文清洗:去除控制字符,并保留白名单字段与对象标识")
  void shouldSanitizeControlChars_whenContextAllowlisted() {
    AiPageContextRequest context = new AiPageContextRequest();
    context.setPageType(" job\u0000detail ");
    context.setObjectType("jobInstance");
    context.setObjectId("demo-1");
    String result = ConsoleAiContextSanitizer.sanitize("v1", Map.of(), context, 200);

    assertThat(result).contains("jobdetail", "objectType", "demo-1").doesNotContain("\u0000");
  }

  @Test
  @DisplayName("上下文清洗:出现白名单之外的字段时拒绝")
  void shouldRejectContext_whenFieldOutsideAllowlist() {
    Map<String, Object> context = Map.of("diagnostic", "contains sensitive details");

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", context, null, 2000))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("上下文清洗:序列化后超出长度上限时拒绝")
  void shouldRejectContext_whenSerializedSizeExceeded() {
    Map<String, Object> oversizedContext = Map.of("pageType", "x".repeat(40));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", oversizedContext, null, 32))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("上下文清洗:嵌套层级超过上限时拒绝")
  void shouldRejectContext_whenNestingTooDeep() {
    Map<String, Object> context = Map.of("objectId", List.of("nested"));

    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v1", context, null, 2000))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("上下文清洗:上下文版本不受支持时拒绝")
  void shouldRejectContext_whenVersionUnsupported() {
    assertThatThrownBy(() -> ConsoleAiContextSanitizer.sanitize("v2", Map.of(), null, 2000))
        .isInstanceOf(BizException.class);
  }
}
