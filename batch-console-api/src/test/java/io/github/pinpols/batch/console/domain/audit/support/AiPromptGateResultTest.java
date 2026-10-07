package io.github.pinpols.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.AiPromptCategory;
import io.github.pinpols.batch.common.enums.AiPromptDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("提示词准入结果: 放行与拒绝两种构造的字段承载")
class AiPromptGateResultTest {

  @Test
  @DisplayName("放行结果携带分类与归一化提示词, 且无拒绝原因")
  void shouldCarryCategoryAndPromptWhenApproved() {
    AiPromptGateResult r = AiPromptGateResult.approved(AiPromptCategory.PLATFORM, "hello");
    assertThat(r.approved()).isTrue();
    assertThat(r.decision()).isEqualTo(AiPromptDecision.APPROVED);
    assertThat(r.category()).isEqualTo(AiPromptCategory.PLATFORM);
    assertThat(r.normalizedPrompt()).isEqualTo("hello");
    assertThat(r.reason()).isNull();
  }

  @Test
  @DisplayName("拒绝结果携带决策与原因, 且归一化提示词为空")
  void shouldCarryDecisionAndReasonWhenRejected() {
    AiPromptGateResult r = AiPromptGateResult.rejected(
        AiPromptDecision.REJECTED_SAFETY, AiPromptCategory.FILE_GOVERNANCE, "policy");
    assertThat(r.approved()).isFalse();
    assertThat(r.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
    assertThat(r.reason()).isEqualTo("policy");
    assertThat(r.normalizedPrompt()).isNull();
  }
}
