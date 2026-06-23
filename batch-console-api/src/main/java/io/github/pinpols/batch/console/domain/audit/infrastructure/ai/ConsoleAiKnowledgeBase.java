package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Console AI 检索增强(RAG)知识库:把系统自身的权威语料(概念 / 状态机 / 错误码 / 故障处理 / 运维)向量化,
 * 提问时检索最相关片段注入提示词,让托管模型基于事实作答而非泛化幻觉。
 *
 * <p>实现刻意保持轻量:用既有的 {@link EmbeddingModel}(随 openai starter 自动装配)做嵌入 + 进程内余弦相似度检索,
 * <b>不引入向量库依赖</b>。语料规模小(几个 markdown,几十个切片),内存索引足够。
 *
 * <p>降级策略:嵌入模型不可用(未配置 key)或检索异常时返回空列表 —— 此时助手退化为「仅领域 primer」回答, 不会因 RAG
 * 不可用而整体不可用。索引在首次检索时懒加载构建,避免启动期联网。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleAiKnowledgeBase {

  private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
  private final ConsoleAiProperties properties;
  private final ResourcePatternResolver resourceResolver =
      new PathMatchingResourcePatternResolver();

  private final Object indexLock = new Object();
  private volatile List<Chunk> index;

  /** 检索与 query 最相关的若干片段(已按相似度降序、过滤低于阈值、截断到 topK)。RAG 关闭或无可用片段时返回空。 */
  public List<Snippet> retrieve(String query) {
    ConsoleAiProperties.Rag rag = properties.getRag();
    if (!rag.isEnabled() || query == null || query.isBlank()) {
      return List.of();
    }
    List<Chunk> chunks = ensureIndex();
    if (chunks.isEmpty()) {
      return List.of();
    }
    EmbeddingModel model = embeddingModelProvider.getIfAvailable();
    if (model == null) {
      return List.of();
    }
    float[] queryVector;
    try {
      queryVector = model.embed(query);
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(ConsoleAiKnowledgeBase.class, "catch:embed-query-failed", ex);
      return List.of();
    }
    return chunks.stream()
        .map(
            chunk -> new Snippet(chunk.source(), chunk.text(), cosine(queryVector, chunk.vector())))
        .filter(snippet -> snippet.score() >= rag.getMinScore())
        .sorted(Comparator.comparingDouble(Snippet::score).reversed())
        .limit(Math.max(rag.getTopK(), 1))
        .toList();
  }

  private List<Chunk> ensureIndex() {
    List<Chunk> local = index;
    if (local != null) {
      return local;
    }
    synchronized (indexLock) {
      if (index == null) {
        index = buildIndex();
      }
      return index;
    }
  }

  private List<Chunk> buildIndex() {
    EmbeddingModel model = embeddingModelProvider.getIfAvailable();
    if (model == null) {
      log.info(
          "console AI RAG: embedding model unavailable, knowledge base disabled (primer-only)");
      return List.of();
    }
    List<Document> documents = loadAndSplit();
    if (documents.isEmpty()) {
      log.warn(
          "console AI RAG: no knowledge documents found under {}",
          properties.getRag().getLocations());
      return List.of();
    }
    List<String> texts = documents.stream().map(Document::getText).toList();
    List<float[]> vectors;
    try {
      vectors = model.embed(texts);
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(ConsoleAiKnowledgeBase.class, "catch:embed-corpus-failed", ex);
      return List.of();
    }
    List<Chunk> chunks = new ArrayList<>(documents.size());
    for (int i = 0; i < documents.size() && i < vectors.size(); i++) {
      Object source = documents.get(i).getMetadata().get("source");
      chunks.add(
          new Chunk(
              source == null ? "knowledge" : source.toString(), texts.get(i), vectors.get(i)));
    }
    log.info(
        "console AI RAG: knowledge base built, {} chunk(s) from {} location(s)",
        chunks.size(),
        properties.getRag().getLocations().size());
    return List.copyOf(chunks);
  }

  private List<Document> loadAndSplit() {
    TokenTextSplitter splitter = TokenTextSplitter.builder().build();
    List<Document> documents = new ArrayList<>();
    for (String location : properties.getRag().getLocations()) {
      for (Resource resource : resolve(location)) {
        String text = read(resource);
        if (text == null || text.isBlank()) {
          continue;
        }
        String source = resource.getFilename() == null ? location : resource.getFilename();
        documents.addAll(splitter.apply(List.of(new Document(text, Map.of("source", source)))));
      }
    }
    return documents;
  }

  private List<Resource> resolve(String location) {
    try {
      return List.of(resourceResolver.getResources(location));
    } catch (IOException ex) {
      SwallowedExceptionLogger.info(
          ConsoleAiKnowledgeBase.class, "catch:resolve-location-failed", ex);
      return List.of();
    }
  }

  private String read(Resource resource) {
    try {
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      SwallowedExceptionLogger.info(ConsoleAiKnowledgeBase.class, "catch:read-resource-failed", ex);
      return null;
    }
  }

  private static double cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length == 0 || a.length != b.length) {
      return 0.0;
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0.0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /** 检索命中的片段:来源文件名 + 文本 + 相似度分。 */
  public record Snippet(String source, String text, double score) {}

  private record Chunk(String source, String text, float[] vector) {}
}
