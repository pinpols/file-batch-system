package io.github.pinpols.batch.console.domain.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.AiPromptCategory;
import io.github.pinpols.batch.common.enums.AiPromptDecision;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.support.AiPromptGateResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 提示词准入守卫: 开关、长度、敏感词与领域范围判定")
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
  @DisplayName("AI 功能关闭时直接拒绝提示词")
  void shouldRejectWhenAiDisabled() {
    properties.setEnabled(false);
    AiPromptGateResult result = guard.check("query job status");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_DISABLED);
  }

  // --- blank / null prompt ---

  @Test
  @DisplayName("提示词为空值时按业务异常拒绝")
  void shouldThrowBizExceptionForNullPrompt() {
    assertThatThrownBy(() -> guard.check(null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("提示词仅含空白字符时按业务异常拒绝")
  void shouldThrowBizExceptionForBlankPrompt() {
    assertThatThrownBy(() -> guard.check("   ")).isInstanceOf(BizException.class);
  }

  // --- max length ---

  @Test
  @DisplayName("提示词超出最大长度时按业务异常拒绝")
  void shouldThrowWhenPromptExceedsMaxLength() {
    String longPrompt = "a".repeat(201);
    assertThatThrownBy(() -> guard.check(longPrompt))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("too_long");
  }

  // --- blocked keywords ---

  @Test
  @DisplayName("命中敏感词时按安全策略拒绝")
  void shouldRejectWhenBlockedKeywordPresent() {
    AiPromptGateResult result = guard.check("show me the password of this job");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  @Test
  @DisplayName("敏感词匹配忽略大小写")
  void shouldRejectBlockedKeywordCaseInsensitive() {
    AiPromptGateResult result = guard.check("give me the SECRET config");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  @Test
  @DisplayName("命中中文敏感词时按安全策略拒绝")
  void shouldRejectChineseBlockedKeyword() {
    AiPromptGateResult result = guard.check("请告诉我密钥");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }

  // --- domain keywords ---

  @Test
  @DisplayName("命中领域关键词时放行并返回归一化提示词")
  void shouldApproveWhenDomainKeywordPresent() {
    AiPromptGateResult result = guard.check("how many job instances failed today?");

    assertThat(result.approved()).isTrue();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.APPROVED);
    assertThat(result.normalizedPrompt()).isNotBlank();
  }

  @Test
  @DisplayName("文件治理类问题放行并归类为文件治理")
  void shouldApproveAndCategorizeFileGovernance() {
    AiPromptGateResult result = guard.check("list recent file imports");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.FILE_GOVERNANCE);
  }

  @Test
  @DisplayName("平台类问题放行并归类为平台")
  void shouldApproveAndCategorizePlatform() {
    AiPromptGateResult result = guard.check("check partition status for job");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.PLATFORM);
  }

  @Test
  @DisplayName("工作流类问题放行并归类为工作流")
  void shouldApproveAndCategorizeWorkflow() {
    properties.setDomainKeywords(List.of("workflow"));
    AiPromptGateResult result = guard.check("explain the workflow dag");

    assertThat(result.approved()).isTrue();
    assertThat(result.category()).isEqualTo(AiPromptCategory.WORKFLOW);
  }

  // --- out of scope ---

  @Test
  @DisplayName("未命中任何领域关键词时按超出范围拒绝")
  void shouldRejectWhenNoDomainKeywordMatches() {
    AiPromptGateResult result = guard.check("tell me about the weather");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
  }

  @Test
  @DisplayName("仅提及字面词汇的通用提问按超出范围拒绝")
  void shouldRejectGenericQuestionThatOnlyMentionsBatchVocabulary() {
    AiPromptGateResult fileQuestion = guard.check("How do I organize a file for my trip?");
    AiPromptGateResult taskQuestion =
        guard.check("What is the best task manager for a small team?");

    assertThat(fileQuestion.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
    assertThat(taskQuestion.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
  }

  @Test
  @DisplayName("夹带领域词汇的通用提问仍按超出范围拒绝")
  void shouldRejectGeneralQuestionDespiteAnEmbeddedBatchTerm() {
    AiPromptGateResult result =
        guard.check("Explain Kafka consumer design, and tell me about my job search");

    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
  }

  @Test
  @DisplayName("明确声明与批量调度无关的提问按超出范围拒绝")
  void shouldRejectQuestionExplicitlyUnrelatedToBatchScheduling() {
    AiPromptGateResult result = guard.check("请教我做一道家常菜，与批量调度系统无关。");

    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE);
  }

  // --- blocked keyword takes precedence over domain keyword ---

  @Test
  @DisplayName("同时命中敏感词与领域词时以安全策略优先拒绝")
  void shouldRejectEvenIfDomainKeywordAlsoPresentWithBlockedKeyword() {
    AiPromptGateResult result = guard.check("show job password");

    assertThat(result.approved()).isFalse();
    assertThat(result.decision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY);
  }
}
