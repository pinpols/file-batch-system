package com.example.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.console.config.ConsoleAiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

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
        .thenAnswer(
            inv ->
                ((List<String>) inv.getArgument(0))
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
  void retrievesRankedKnowledgePackSnippetsForInDomainQuery() {
    ConsoleAiKnowledgeBase base =
        new ConsoleAiKnowledgeBase(providerOf(fakeEmbeddingModel()), propertiesWithRag(true));

    List<ConsoleAiKnowledgeBase.Snippet> snippets =
        base.retrieve("outbox event kafka claim report orchestrator");

    assertThat(snippets).isNotEmpty();
    assertThat(snippets).allSatisfy(s -> assertThat(s.source()).endsWith(".md"));
    assertThat(snippets).hasSizeLessThanOrEqualTo(3);
    // 降序排序
    for (int i = 1; i < snippets.size(); i++) {
      assertThat(snippets.get(i - 1).score()).isGreaterThanOrEqualTo(snippets.get(i).score());
    }
    // 命中片段应包含与查询相关的内容
    assertThat(snippets).anySatisfy(s -> assertThat(s.text().toLowerCase()).contains("outbox"));
  }

  @Test
  void returnsEmptyWhenRagDisabled() {
    ConsoleAiKnowledgeBase base =
        new ConsoleAiKnowledgeBase(providerOf(fakeEmbeddingModel()), propertiesWithRag(false));
    assertThat(base.retrieve("orchestrator outbox")).isEmpty();
  }

  @Test
  void returnsEmptyWhenEmbeddingModelUnavailable() {
    ObjectProvider<EmbeddingModel> empty = mockEmbeddingProvider();
    when(empty.getIfAvailable()).thenReturn(null);
    ConsoleAiKnowledgeBase base = new ConsoleAiKnowledgeBase(empty, propertiesWithRag(true));
    assertThat(base.retrieve("orchestrator outbox")).isEmpty();
  }
}
