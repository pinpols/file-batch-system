package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按租户和所有者隔离持久化 AI 会话。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiConversationService {

  private static final String SUPPORTED_CONTEXT_VERSION = "v1";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String COMPLETE = "COMPLETE";
  private static final String FAILED = "FAILED";
  private static final String REJECTED = "REJECTED";

  private final ConsoleAiConversationMapper mapper;
  private final ConsoleAiProperties properties;
  private final BatchObjectCryptoService cryptoService;

  @Transactional
  public StartedTurn beginTurn(
      String tenantId,
      String ownerUserId,
      String requestedConversationId,
      String contextVersion,
      String prompt) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    if (!SUPPORTED_CONTEXT_VERSION.equals(contextVersion)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail");
    }
    mapper.setTenantContext(tenantId);

    String conversationId = Texts.hasText(requestedConversationId)
        ? requestedConversationId
        : UUID.randomUUID().toString();
    OffsetDateTime now = nowUtc();
    OffsetDateTime expiresAt =
        now.plus(Duration.ofDays(properties.getPersistence().getRetentionDays()));
    ConsoleAiConversationEntity conversation = mapper.selectForUpdate(conversationId);
    if (EmptyChecks.isNull(conversation)) {
      conversation = new ConsoleAiConversationEntity();
      conversation.setId(conversationId);
      conversation.setTenantId(tenantId);
      conversation.setOwnerUserId(ownerUserId);
      conversation.setTitle("AI conversation");
      conversation.setContextVersion(contextVersion);
      conversation.setExpiresAt(expiresAt);
      mapper.insertConversation(conversation);
    } else if (!ownerUserId.equals(conversation.getOwnerUserId())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.common.forbidden_detail");
    }

    List<ConsoleAiTurnEntity> history = mapper.selectRecentCompleteTurns(
        conversationId, properties.getPersistence().getMaxHistoryTurns());
    Collections.reverse(history);
    history = boundHistory(history, properties.getPersistence().getMaxHistoryChars());
    Long turnNo = mapper.allocateTurnNo(conversationId, ownerUserId, expiresAt, contextVersion);
    if (EmptyChecks.isNull(turnNo)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.common.forbidden_detail");
    }
    ConsoleAiTurnEntity turn = new ConsoleAiTurnEntity();
    turn.setTenantId(tenantId);
    turn.setConversationId(conversationId);
    turn.setTurnNo(turnNo);
    turn.setContextVersion(contextVersion);
    turn.setPromptText(
        encryptText(ConsoleTextSanitizer.safeInput(prompt, properties.getMaxPromptLength())));
    mapper.insertTurn(turn);
    return new StartedTurn(conversationId, turnNo, history);
  }

  @Transactional
  public void completeTurn(
      String tenantId,
      String conversationId,
      long turnNo,
      String response,
      String decision,
      String modelName,
      Integer promptTokens,
      Integer completionTokens,
      java.math.BigDecimal estimatedCostUsd) {
    mapper.setTenantContext(tenantId);
    String status =
        switch (EmptyChecks.isNull(decision) ? "" : decision) {
          case "FAILED" -> FAILED;
          case "REJECTED_SCOPE", "REJECTED_SAFETY", "REJECTED_DISABLED", "REJECTED_BUDGET" ->
            REJECTED;
          default -> COMPLETE;
        };
    int updated = mapper.completeTurn(
        tenantId,
        conversationId,
        turnNo,
        encryptText(ConsoleTextSanitizer.safeInput(response, properties.getMaxResponseLength())),
        status,
        decision,
        modelName,
        promptTokens,
        completionTokens,
        estimatedCostUsd);
    if (updated != 1) {
      throw new IllegalStateException("AI conversation turn was not in progress");
    }
  }

  @Transactional(readOnly = true)
  public List<ConversationView> list(String tenantId, String ownerUserId, int requestedLimit) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    mapper.setTenantContext(tenantId);
    int limit = Math.min(
        Math.max(requestedLimit, 1), properties.getPersistence().getConversationPageSize());
    return mapper.selectByOwner(ownerUserId, limit).stream()
        .map(row -> new ConversationView(
            row.getId(),
            row.getTitle(),
            row.getContextVersion(),
            row.getCreatedAt(),
            row.getUpdatedAt(),
            row.getExpiresAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<TurnView> turns(
      String tenantId,
      String ownerUserId,
      String conversationId,
      Long beforeTurnNo,
      int requestedLimit) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    mapper.setTenantContext(tenantId);
    int limit = Math.min(Math.max(requestedLimit, 1), 100);
    List<TurnView> rows =
        mapper.selectTurns(conversationId, ownerUserId, beforeTurnNo, limit).stream()
            .map(row -> new TurnView(
                row.getTurnNo(),
                row.getContextVersion(),
                decryptText(row.getPromptText()),
                decryptText(row.getResponseText()),
                row.getTurnStatus(),
                row.getPromptDecision(),
                row.getModelName(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getEstimatedCostUsd(),
                row.getCreatedAt(),
                row.getCompletedAt()))
            .toList();
    return rows;
  }

  @Transactional
  public void delete(String tenantId, String ownerUserId, String conversationId) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    mapper.setTenantContext(tenantId);
    mapper.deleteConversation(conversationId, ownerUserId);
  }

  @Transactional
  public int deleteExpiredForTenant(String tenantId) {
    mapper.setTenantContext(tenantId);
    return mapper.deleteExpired(nowUtc());
  }

  private List<ConsoleAiTurnEntity> boundHistory(List<ConsoleAiTurnEntity> turns, int maxChars) {
    List<ConsoleAiTurnEntity> bounded = new ArrayList<>();
    int remaining = Math.max(0, maxChars);
    for (int i = turns.size() - 1; i >= 0 && remaining > 0; i--) {
      ConsoleAiTurnEntity turn = turns.get(i);
      turn.setPromptText(decryptText(turn.getPromptText()));
      turn.setResponseText(decryptText(turn.getResponseText()));
      int promptLength =
          EmptyChecks.isNull(turn.getPromptText()) ? 0 : turn.getPromptText().length();
      int responseLength = EmptyChecks.isNull(turn.getResponseText())
          ? 0
          : turn.getResponseText().length();
      int turnLength = promptLength + responseLength;
      if (turnLength > remaining) {
        continue;
      }
      bounded.add(turn);
      remaining -= turnLength;
    }
    Collections.reverse(bounded);
    return bounded;
  }

  private void requirePersistenceEnabled() {
    if (!properties.getPersistence().isEnabled()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.ai.assistant_not_configured");
    }
    if (cryptoService.isBypassMode()) {
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, "error.ai.assistant_not_configured");
    }
  }

  private String encryptText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    return Base64.getEncoder()
        .encodeToString(cryptoService.encrypt(value.getBytes(StandardCharsets.UTF_8), null));
  }

  private String decryptText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    byte[] ciphertext = Base64.getDecoder().decode(value);
    return new String(cryptoService.decrypt(ciphertext), StandardCharsets.UTF_8);
  }

  private static void requireOwner(String ownerUserId) {
    if (!Texts.hasText(ownerUserId)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.common.forbidden_detail");
    }
  }

  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.ofInstant(BatchDateTimeSupport.utcNow(), ZoneOffset.UTC);
  }

  public record StartedTurn(
      String conversationId, long turnNo, List<ConsoleAiTurnEntity> history) {}

  public record ConversationView(
      String id,
      String title,
      String contextVersion,
      Instant createdAt,
      Instant updatedAt,
      OffsetDateTime expiresAt) {}

  public record TurnView(
      long turnNo,
      String contextVersion,
      String prompt,
      String response,
      String status,
      String promptDecision,
      String modelName,
      Integer promptTokens,
      Integer completionTokens,
      java.math.BigDecimal estimatedCostUsd,
      Instant createdAt,
      Instant completedAt) {}
}
