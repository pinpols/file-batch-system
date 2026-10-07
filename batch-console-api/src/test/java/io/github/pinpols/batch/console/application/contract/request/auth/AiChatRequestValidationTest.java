package io.github.pinpols.batch.console.application.contract.request.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 对话请求参数校验: 会话标识长度上限守护")
class AiChatRequestValidationTest {

  @Test
  @DisplayName("sessionId 超过 audit 列上限 128 时请求校验拒绝")
  void shouldReject_whenSessionIdExceedsAuditColumnLimit() {
    AiChatRequest request = new AiChatRequest();
    request.setPrompt("diagnose");
    request.setSessionId("s".repeat(129));

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request))
          .anyMatch(violation -> violation.getPropertyPath().toString().equals("sessionId"));
    }
  }
}
