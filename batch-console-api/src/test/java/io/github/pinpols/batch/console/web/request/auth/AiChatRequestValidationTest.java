package io.github.pinpols.batch.console.web.request.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiChatRequestValidationTest {

  @Test
  @DisplayName("sessionId 超过 audit 列上限 128 时请求校验拒绝")
  void rejectsSessionIdLongerThanAuditColumn() {
    AiChatRequest request = new AiChatRequest();
    request.setPrompt("diagnose");
    request.setSessionId("s".repeat(129));

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request))
          .anyMatch(violation -> violation.getPropertyPath().toString().equals("sessionId"));
    }
  }
}
