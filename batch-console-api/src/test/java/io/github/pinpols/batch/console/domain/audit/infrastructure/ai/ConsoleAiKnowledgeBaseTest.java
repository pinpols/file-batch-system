package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

@DisplayName("AI 知识库检索:排序结果、降级开关与语料一致性")
class ConsoleAiKnowledgeBaseTest {

  /** 词袋伪嵌入:同一文本里出现的词散列进 64 维,词重叠越多余弦越高 —— 足以确定性验证检索排序。 */
  private static float[] bagOfWords(String text) {
    float[] vector = new float[64];
    for (String token : text.toLowerCase().split("\\W+")) {
      if (!token.isBlank()) {
        vector[Math.floorMod(token.hashCode(), 64)] += 1f;
      }
    }
    return vector;
  }

  @SuppressWarnings("unchecked")
  private EmbeddingModel fakeEmbeddingModel() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.embed(anyString())).thenAnswer(inv -> bagOfWords(inv.getArgument(0)));
    when(model.embed(anyList()))
        .thenAnswer(inv -> ((List<String>) inv.getArgument(0))
            .stream().map(ConsoleAiKnowledgeBaseTest::bagOfWords).toList());
    return model;
  }

  private ObjectProvider<EmbeddingModel> providerOf(EmbeddingModel model) {
    ObjectProvider<EmbeddingModel> provider = mockEmbeddingProvider();
    when(provider.getIfAvailable()).thenReturn(model);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<EmbeddingModel> mockEmbeddingProvider() {
    return (ObjectProvider<EmbeddingModel>) mock(ObjectProvider.class);
  }

  private ConsoleAiProperties propertiesWithRag(boolean enabled) {
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.getRag().setEnabled(enabled);
    properties.getRag().setMinScore(0.0);
    properties.getRag().setTopK(3);
    return properties;
  }

  @Test
  @DisplayName("知识检索:域内查询返回按得分降序的片段,且来源为文档")
  void shouldReturnRankedSnippets_whenQueryInDomain() {
    ConsoleAiKnowledgeBase base =
        new ConsoleAiKnowledgeBase(providerOf(fakeEmbeddingModel()), propertiesWithRag(true));

    List<ConsoleAiKnowledgeBase.Snippet> snippets =
        base.retrieve("outbox event kafka claim report orchestrator");

    assertThat(snippets).isNotEmpty();
    assertThat(snippets).allSatisfy(s -> assertThat(s.source()).endsWith(".md"));
    assertThat(snippets).hasSizeLessThanOrEqualTo(3);
    // 降序排序
    for (int i = 1; i < snippets.size(); i++) {
      assertThat(snippets.get(i - 1).score())
          .isGreaterThanOrEqualTo(snippets.get(i).score());
    }
    // 命中片段应包含与查询相关的内容
    assertThat(snippets).anySatisfy(s -> assertThat(s.text().toLowerCase()).contains("outbox"));
  }

  @Test
  @DisplayName("知识检索:告警分诊与数据质量两份语料均可命中")
  void shouldRetrieveCorpus_whenQueryingAlertTriageAndDataQuality() {
    ConsoleAiKnowledgeBase base =
        new ConsoleAiKnowledgeBase(providerOf(fakeEmbeddingModel()), propertiesWithRag(true));

    // 告警分诊语料(08)可检索
    assertThat(base.retrieve(
            "alert severity CRITICAL occurrenceCount alertType status OPEN escalation triage"))
        .isNotEmpty();
    // DQ 规则草稿语料(09)可检索
    List<ConsoleAiKnowledgeBase.Snippet> dq = base.retrieve(
        "data quality ruleType TABLE_LEVEL expression thresholdJson severity BLOCKER draft");
    assertThat(dq).isNotEmpty();
    assertThat(dq).anySatisfy(s -> assertThat(s.text()).contains("ruleType"));
  }

  @Test
  @DisplayName("检索开关关闭:返回空结果")
  void shouldReturnEmpty_whenRetrievalDisabled() {
    ConsoleAiKnowledgeBase base =
        new ConsoleAiKnowledgeBase(providerOf(fakeEmbeddingModel()), propertiesWithRag(false));
    assertThat(base.retrieve("orchestrator outbox")).isEmpty();
  }

  @Test
  @DisplayName("嵌入模型不可用:返回空结果")
  void shouldReturnEmpty_whenEmbeddingModelMissing() {
    ObjectProvider<EmbeddingModel> empty = mockEmbeddingProvider();
    when(empty.getIfAvailable()).thenReturn(null);
    ConsoleAiKnowledgeBase base = new ConsoleAiKnowledgeBase(empty, propertiesWithRag(true));
    assertThat(base.retrieve("orchestrator outbox")).isEmpty();
  }

  @Test
  @DisplayName("状态语料:覆盖作业实例、分区、任务与工作流运行的全部状态码")
  void shouldCoverAllRuntimeStatusCodes_whenStatusPackRead() throws IOException {
    String statusKnowledge = readKnowledge("ai-knowledge/02-status-and-enums.md");

    assertContainsAllCodes(statusKnowledge, JobInstanceStatus.values());
    assertContainsAllCodes(statusKnowledge, PartitionStatus.values());
    assertContainsAllCodes(statusKnowledge, TaskStatus.values());
    assertContainsAllCodes(statusKnowledge, WorkflowRunStatus.values());
  }

  @Test
  @DisplayName("治理语料:覆盖近期工程护栏关键词")
  void shouldCoverRecentGuardrails_whenGovernancePackRead() throws IOException {
    String concepts = readKnowledge("ai-knowledge/01-concepts.md");
    String operations = readKnowledge("ai-knowledge/05-operations.md");
    String governance = readKnowledge("ai-knowledge/10-engineering-governance.md");

    assertThat(concepts)
        .contains("BatchTaskExecutorRegistry")
        .contains("DispatchChannelGateway")
        .contains("channelType -> adapter");
    assertThat(operations)
        .contains("IMAGE_TAG")
        .contains("ALLOW_PARALLEL_TESTS=1")
        .contains("openai-compatible")
        .contains("BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_CHAT_MODEL");
    assertThat(governance).contains("EmptyChecks").contains("ai-knowledge/*.md");
  }

  private static String readKnowledge(String path) throws IOException {
    return new String(new ClassPathResource(path).getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private static void assertContainsAllCodes(String text, Enum<?>[] values) {
    List<String> codes = Arrays.stream(values).map(Enum::name).toList();
    assertThat(codes).isNotEmpty().allSatisfy(code -> assertThat(text).contains(code));
  }
}
