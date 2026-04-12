package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AiPromptDecision;
import org.junit.jupiter.api.Test;

class AiPromptGateResultTest {

  @Test
  void approvedShouldCarryCategoryAndPrompt() {
    AiPromptGateResult r = AiPromptGateResult.approved(AiPromptCategory.PLATFORM, "hello");
    assertThat(r.approved()).isTrue();
    assertThat(r.decision()).isEqualTo(AiPromptDecision.APPROVED);
    assertThat(r.category()).isEqualTo(AiPromptCategory.PLATFORM);
    assertThat(r.normalizedPrompt()).isEqualTo("hello");
    assertThat(r.reason()).isNull();
  }

  @Test
  void rejectedShouldCarryDecisionAndReason() {
    AiPromptGateResult r =
        AiPromptGateResult.rejected(
            AiPromptDecision.REJECTED_SAFETY, AiPromptCategory.FILE_GOVERNANCE, "policy");
    assertThat(r.approved()).isFalse();
    assertThat(r.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
    assertThat(r.reason()).isEqualTo("policy");
    assertThat(r.normalizedPrompt()).isNull();
  }
}
