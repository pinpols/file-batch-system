package io.github.pinpols.batch.console.domain.audit.support;

import io.github.pinpols.batch.common.enums.AiPromptCategory;
import io.github.pinpols.batch.common.enums.AiPromptDecision;

public record AiPromptGateResult(
    boolean approved,
    AiPromptDecision decision,
    AiPromptCategory category,
    String reason,
    String normalizedPrompt) {
  public static AiPromptGateResult approved(AiPromptCategory category, String normalizedPrompt) {
    return new AiPromptGateResult(
        true, AiPromptDecision.APPROVED, category, null, normalizedPrompt);
  }

  public static AiPromptGateResult rejected(
      AiPromptDecision decision, AiPromptCategory category, String reason) {
    return new AiPromptGateResult(false, decision, category, reason, null);
  }
}
