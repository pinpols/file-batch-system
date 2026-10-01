package io.github.pinpols.batch.console.domain.audit.application.ai;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.request.auth.AiChatRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiChatResponse;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import reactor.core.publisher.Mono;

/** 控制台 AI 对话应用服务：基于 Spring AI 的聊天与审计写入数据库。 */
public interface ConsoleAiApplicationService {

  /** 调用方须在异步分派前完成授权并捕获请求元数据。 */
  AiChatResponse chatStream(
      AiChatRequest request,
      String idempotencyKey,
      ConsoleRequestMetadata metadata,
      StreamObserver observer);

  interface StreamObserver {
    void onDelta(String text);

    boolean isCancelled();

    boolean hasEmitted();

    Mono<Void> cancellationSignal();
  }

  List<ConversationSummary> conversations(String tenantId, String ownerUserId, int limit);

  PageResponse<ConversationSummary> conversationPage(
      String tenantId, String ownerUserId, String cursor, int limit);

  List<TurnSummary> turns(
      String tenantId, String ownerUserId, String conversationId, Long beforeTurnNo, int limit);

  void deleteConversation(String tenantId, String ownerUserId, String conversationId);

  AiCostSummary costSummary(String tenantId, YearMonth month);

  record ConversationSummary(
      String id,
      String title,
      String contextVersion,
      Instant createdAt,
      Instant updatedAt,
      OffsetDateTime expiresAt) {}

  record TurnSummary(
      long turnNo,
      String contextVersion,
      String prompt,
      String response,
      String status,
      String promptDecision,
      String modelName,
      Integer promptTokens,
      Integer completionTokens,
      BigDecimal estimatedCostUsd,
      Instant createdAt,
      Instant completedAt) {}

  record AiCostSummary(
      YearMonth month,
      long requestCount,
      long promptTokens,
      long completionTokens,
      BigDecimal estimatedCostUsd,
      BigDecimal reservedCostUsd,
      BigDecimal monthlyBudgetUsd) {}
}
