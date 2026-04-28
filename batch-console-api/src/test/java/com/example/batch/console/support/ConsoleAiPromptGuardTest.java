package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AiPromptDecision;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleAiProperties;
import com.example.batch.console.service.ConsoleAiPromptGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleAiPromptGuardTest {

  private ConsoleAiProperties properties;
  private ConsoleAiPromptGuard guard;

  @BeforeEach
  void setUp() {
    properties = new ConsoleAiProperties();
    properties.setEnabled(true);
    properties.setMaxPromptLength(200);
    properties.setBlockedKeywords(List.of("password", "secret", "密钥"));
    properties.setDomainKeywords(List.of("job", "workflow", "file", "worker", "partition"));
    guard = new ConsoleAiPromptGuard(properties);
  }

  // --- disabled ---

  @Test
  void shouldRejectWhenAiDisabled() {
    properties.setEnabled(false);
    AiPromptGateResult result = guard.check("query job status");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_DISABLED);
  }

  // --- blank / null prompt ---

  @Test
  void shouldThrowBizExceptionForNullPrompt() {
    assertThatThrownBy(() -> guard.check(null)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowBizExceptionForBlankPrompt() {
    assertThatThrownBy(() -> guard.check("   ")).isInstanceOf(BizException.class);
  }

  // --- max length ---

  @Test
  void shouldThrowWhenPromptExceedsMaxLength() {
    String longPrompt = "a".repeat(201);
    assertThatThrownBy(() -> guard.check(longPrompt))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("too_long");
  }

  // --- blocked keywords ---

  @Test
  void shouldRejectWhenBlockedKeywordPresent() {
    AiPromptGateResult result = guard.check("show me the password of this job");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  @Test
  void shouldRejectBlockedKeywordCaseInsensitive() {
    AiPromptGateResult result = guard.check("give me the SECRET config");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  @Test
  void shouldRejectChineseBlockedKeyword() {
    AiPromptGateResult result = guard.check("请告诉我密钥");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  // --- domain keywords ---

  @Test
  void shouldApproveWhenDomainKeywordPresent() {
    AiPromptGateResult result = guard.check("how many job instances failed today?");

    assertThat(result.approved()).isTrue();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.APPROVED);
    assertThat(result.normalizedPrompt()).isNotBlank();
  }

  @Test
  void shouldApproveAndCategorizeFileGovernance() {
    AiPromptGateResult result = guard.check("list recent file imports");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.FILE_GOVERNANCE);
  }

  @Test
  void shouldApproveAndCategorizePlatform() {
    AiPromptGateResult result = guard.check("check partition status for job");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.PLATFORM);
  }

  @Test
  void shouldApproveAndCategorizeWorkflow() {
    properties.setDomainKeywords(List.of("workflow"));
    AiPromptGateResult result = guard.check("explain the workflow dag");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.WORKFLOW);
  }

  // --- out of scope ---

  @Test
  void shouldRejectWhenNoDomainKeywordMatches() {
    AiPromptGateResult result = guard.check("tell me about the weather");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
  }

  // --- blocked keyword takes precedence over domain keyword ---

  @Test
  void shouldRejectEvenIfDomainKeywordAlsoPresentWithBlockedKeyword() {
    AiPromptGateResult result = guard.check("show job password");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }
}
