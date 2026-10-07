package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.page.CursorCodec;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
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
  private final ConsoleAiAttachmentService attachmentService;

  @Transactional
  public StartedTurn beginTurn(
      String tenantId,
      String ownerUserId,
      String requestedConversationId,
      String contextVersion,
      String prompt) {
    return beginTurn(
        tenantId, ownerUserId, requestedConversationId, contextVersion, prompt, null, null);
  }

  @Transactional
  public StartedTurn beginTurn(
      String tenantId,
      String ownerUserId,
      String requestedConversationId,
      String contextVersion,
      String prompt,
      UUID clientTurnId,
      List<UUID> attachmentIds) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    if (!SUPPORTED_CONTEXT_VERSION.equals(contextVersion)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
    mapper.setTenantContext(tenantId);

    if (EmptyChecks.isNotNull(clientTurnId)
        && EmptyChecks.isNotNull(
            mapper.selectByClientTurnId(tenantId, ownerUserId, clientTurnId))) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }

    boolean creating = !Texts.hasText(requestedConversationId);
    String conversationId = creating ? UUID.randomUUID().toString() : requestedConversationId;
    OffsetDateTime now = nowUtc();
    OffsetDateTime expiresAt =
        now.plus(Duration.ofDays(properties.getPersistence().getRetentionDays()));
    ConsoleAiConversationEntity conversation = mapper.selectForUpdate(tenantId, conversationId);
    if (EmptyChecks.isNull(conversation)) {
      if (!creating) {
        throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
      }
      conversation = new ConsoleAiConversationEntity();
      conversation.setId(conversationId);
      conversation.setTenantId(tenantId);
      conversation.setOwnerUserId(ownerUserId);
      conversation.setTitle("AI conversation");
      conversation.setContextVersion(contextVersion);
      conversation.setExpiresAt(expiresAt);
      mapper.insertConversation(conversation);
    } else if (!ownerUserId.equals(conversation.getOwnerUserId())) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    } else if (!conversation.getExpiresAt().isAfter(now)) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }

    List<ConsoleAiTurnEntity> history = mapper.selectRecentCompleteTurns(
        tenantId, conversationId, properties.getPersistence().getMaxHistoryTurns());
    Collections.reverse(history);
    history = boundHistory(history, properties.getPersistence().getMaxHistoryChars());
    Long turnNo =
        mapper.allocateTurnNo(tenantId, conversationId, ownerUserId, expiresAt, contextVersion);
    if (EmptyChecks.isNull(turnNo)) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    ConsoleAiTurnEntity turn = new ConsoleAiTurnEntity();
    turn.setTenantId(tenantId);
    turn.setConversationId(conversationId);
    turn.setTurnNo(turnNo);
    turn.setClientTurnId(clientTurnId);
    turn.setContextVersion(contextVersion);
    turn.setPromptText(
        encryptText(ConsoleTextSanitizer.safeInput(prompt, properties.getMaxPromptLength())));
    if (mapper.insertTurn(turn) != 1) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
    attachmentService.bind(
        tenantId, ownerUserId, attachmentIds, conversationId, turnNo, expiresAt.toInstant());
    return new StartedTurn(conversationId, turnNo, history);
  }

  @Transactional
  public void completeTurn(TurnCompletion completion) {
    mapper.setTenantContext(completion.tenantId());
    String status =
        switch (EmptyChecks.isNull(completion.decision()) ? "" : completion.decision()) {
          case FAILED -> FAILED;
          case "REJECTED_SCOPE", "REJECTED_SAFETY", "REJECTED_DISABLED", "REJECTED_BUDGET" ->
            REJECTED;
          default -> COMPLETE;
        };
    ConsoleAiTurnEntity turn = new ConsoleAiTurnEntity();
    turn.setTenantId(completion.tenantId());
    turn.setConversationId(completion.conversationId());
    turn.setTurnNo(completion.turnNo());
    turn.setResponseText(encryptText(
        ConsoleTextSanitizer.safeInput(completion.response(), properties.getMaxResponseLength())));
    turn.setTurnStatus(status);
    turn.setPromptDecision(completion.decision());
    turn.setModelName(completion.modelName());
    turn.setPromptTokens(completion.promptTokens());
    turn.setCompletionTokens(completion.completionTokens());
    turn.setEstimatedCostUsd(completion.estimatedCostUsd());
    int updated = mapper.completeTurn(turn);
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
    return mapper.selectByOwner(tenantId, ownerUserId, limit).stream()
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
  public PageResponse<ConversationView> page(
      String tenantId, String ownerUserId, String cursor, int requestedLimit) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    CursorPosition position = decodeCursor(cursor);
    mapper.setTenantContext(tenantId);
    int limit = Math.min(
        Math.max(requestedLimit, 1), properties.getPersistence().getConversationPageSize());
    List<ConsoleAiConversationEntity> rows = mapper.selectPageByOwner(
        tenantId, ownerUserId, position.updatedAt(), position.id(), limit + 1);
    boolean hasMore = rows.size() > limit;
    List<ConsoleAiConversationEntity> visible = rows.subList(0, Math.min(rows.size(), limit));
    List<ConversationView> items = visible.stream()
        .map(row -> new ConversationView(
            row.getId(),
            row.getTitle(),
            row.getContextVersion(),
            row.getCreatedAt(),
            row.getUpdatedAt(),
            row.getExpiresAt()))
        .toList();
    String nextCursor = hasMore
        ? CursorCodec.encode(Map.of(
            "updatedAt", visible.get(visible.size() - 1).getUpdatedAt().toString(),
            "id", visible.get(visible.size() - 1).getId()))
        : null;
    return PageResponse.cursor(items, limit, nextCursor);
  }

  private CursorPosition decodeCursor(String cursor) {
    if (!Texts.hasText(cursor)) {
      return new CursorPosition(null, null);
    }
    if (cursor.length() > 512) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
    Map<String, Object> values = CursorCodec.decode(cursor);
    if (!(values.get("updatedAt") instanceof String updatedAt)
        || !(values.get("id") instanceof String id)
        || EmptyChecks.isBlank(id)
        || id.length() > 128) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
    try {
      return new CursorPosition(Instant.parse(updatedAt), id);
    } catch (java.time.format.DateTimeParseException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
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
    if (EmptyChecks.isNull(mapper.selectActiveByOwner(tenantId, conversationId, ownerUserId))) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    int limit = Math.min(Math.max(requestedLimit, 1), 100);
    List<TurnView> rows =
        mapper.selectTurns(tenantId, conversationId, ownerUserId, beforeTurnNo, limit).stream()
            .map(this::toTurnView)
            .toList();
    return rows;
  }

  @Transactional(readOnly = true)
  public ClientTurnView byClientTurnId(String tenantId, String ownerUserId, UUID clientTurnId) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    mapper.setTenantContext(tenantId);
    ConsoleAiTurnEntity row = mapper.selectByClientTurnId(tenantId, ownerUserId, clientTurnId);
    if (EmptyChecks.isNull(row)) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    return new ClientTurnView(row.getConversationId(), toTurnView(row));
  }

  private TurnView toTurnView(ConsoleAiTurnEntity row) {
    return new TurnView(
        row.getTurnNo(),
        row.getClientTurnId(),
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
        row.getCompletedAt());
  }

  @Transactional
  public void delete(String tenantId, String ownerUserId, String conversationId) {
    requirePersistenceEnabled();
    requireOwner(ownerUserId);
    mapper.setTenantContext(tenantId);
    if (EmptyChecks.isNull(mapper.selectActiveByOwner(tenantId, conversationId, ownerUserId))) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    attachmentService.enqueueConversation(tenantId, conversationId);
    mapper.deleteConversation(tenantId, conversationId, ownerUserId);
  }

  @Transactional
  public int deleteExpiredForTenant(String tenantId) {
    mapper.setTenantContext(tenantId);
    OffsetDateTime now = nowUtc();
    attachmentService.enqueueExpiredConversations(tenantId, now.toInstant());
    return mapper.deleteExpired(tenantId, now);
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
      throw BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey());
    }
  }

  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.ofInstant(BatchDateTimeSupport.utcNow(), ZoneOffset.UTC);
  }

  public record StartedTurn(
      String conversationId, long turnNo, List<ConsoleAiTurnEntity> history) {}

  @Builder
  public record TurnCompletion(
      String tenantId,
      String conversationId,
      long turnNo,
      String response,
      String decision,
      String modelName,
      Integer promptTokens,
      Integer completionTokens,
      BigDecimal estimatedCostUsd) {}

  public record ConversationView(
      String id,
      String title,
      String contextVersion,
      Instant createdAt,
      Instant updatedAt,
      OffsetDateTime expiresAt) {}

  private record CursorPosition(Instant updatedAt, String id) {}

  public record TurnView(
      long turnNo,
      UUID clientTurnId,
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

  public record ClientTurnView(String conversationId, TurnView turn) {}
}
