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
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentBindingInput;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiTurnStartInput;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiConversationAllocateTurnNoParam;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationPageByOwnerQuery;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiConversationTurnsQuery;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
  public StartedTurn beginTurn(ConsoleAiTurnStartInput input) {
    String tenantId = input.tenantId();
    String ownerUserId = input.ownerUserId();
    String requestedConversationId = input.requestedConversationId();
    String contextVersion = input.contextVersion();
    UUID clientTurnId = input.clientTurnId();
    List<UUID> attachmentIds = input.attachmentIds();
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
    int retentionDays = properties.getPersistence().getRetentionDays();
    Duration retentionDuration = Duration.ofDays(retentionDays);
    OffsetDateTime expiresAt = now.plus(retentionDuration);
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
    ConsoleAiConversationAllocateTurnNoParam consoleAiConversationAllocateTurnNoParam =
        ConsoleAiConversationAllocateTurnNoParam.builder()
            .tenantId(tenantId)
            .conversationId(conversationId)
            .ownerUserId(ownerUserId)
            .expiresAt(expiresAt)
            .contextVersion(contextVersion)
            .build();
    Long turnNo = mapper.allocateTurnNo(consoleAiConversationAllocateTurnNoParam);
    if (EmptyChecks.isNull(turnNo)) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    persistStartedTurn(input, conversationId, turnNo);
    ConsoleAiAttachmentBindingInput attachmentBinding = ConsoleAiAttachmentBindingInput.builder()
        .tenantId(tenantId)
        .ownerUserId(ownerUserId)
        .ids(attachmentIds)
        .conversationId(conversationId)
        .turnNo(turnNo)
        .expiresAt(expiresAt.toInstant())
        .build();
    attachmentService.bind(attachmentBinding);
    return new StartedTurn(conversationId, turnNo, history);
  }

  /** 轮次分配后保存加密提示词；插入冲突仍回滚外层事务，附件只在该记录写入成功后绑定。 */
  private void persistStartedTurn(
      ConsoleAiTurnStartInput input, String conversationId, Long turnNo) {
    ConsoleAiTurnEntity turn = new ConsoleAiTurnEntity();
    turn.setTenantId(input.tenantId());
    turn.setConversationId(conversationId);
    turn.setTurnNo(turnNo);
    turn.setClientTurnId(input.clientTurnId());
    turn.setContextVersion(input.contextVersion());
    int maxPromptLength = properties.getMaxPromptLength();
    String sanitizedPrompt = ConsoleTextSanitizer.safeInput(input.prompt(), maxPromptLength);
    String encryptedPrompt = encryptText(sanitizedPrompt);
    turn.setPromptText(encryptedPrompt);
    if (mapper.insertTurn(turn) != 1) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
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
    int maxResponseLength = properties.getMaxResponseLength();
    String sanitizedResponse =
        ConsoleTextSanitizer.safeInput(completion.response(), maxResponseLength);
    String encryptedResponse = encryptText(sanitizedResponse);
    turn.setResponseText(encryptedResponse);
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
    int limit = conversationPageLimit(requestedLimit);
    List<ConsoleAiConversationEntity> conversations =
        mapper.selectByOwner(tenantId, ownerUserId, limit);
    return conversations.stream()
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
    int limit = conversationPageLimit(requestedLimit);
    ConsoleAiConversationPageByOwnerQuery consoleAiConversationPageByOwnerQuery =
        ConsoleAiConversationPageByOwnerQuery.builder()
            .tenantId(tenantId)
            .ownerUserId(ownerUserId)
            .beforeUpdatedAt(position.updatedAt())
            .beforeId(position.id())
            .limit(limit + 1)
            .build();
    List<ConsoleAiConversationEntity> rows =
        mapper.selectPageByOwner(consoleAiConversationPageByOwnerQuery);
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
    String nextCursor = null;
    if (hasMore) {
      ConsoleAiConversationEntity lastVisible = visible.get(visible.size() - 1);
      String lastUpdatedAt = lastVisible.getUpdatedAt().toString();
      String lastConversationId = lastVisible.getId();
      Map<String, Object> cursorValues =
          Map.of("updatedAt", lastUpdatedAt, "id", lastConversationId);
      nextCursor = CursorCodec.encode(cursorValues);
    }
    return PageResponse.cursor(items, limit, nextCursor);
  }

  private int conversationPageLimit(int requestedLimit) {
    int minimumLimit = Math.max(requestedLimit, 1);
    return Math.min(minimumLimit, properties.getPersistence().getConversationPageSize());
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
      Instant parsedUpdatedAt = Instant.parse(updatedAt);
      return new CursorPosition(parsedUpdatedAt, id);
    } catch (DateTimeParseException exception) {
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
    int atLeastOneTurn = Math.max(requestedLimit, 1);
    int limit = Math.min(atLeastOneTurn, 100);
    ConsoleAiConversationTurnsQuery consoleAiConversationTurnsQuery =
        ConsoleAiConversationTurnsQuery.builder()
            .tenantId(tenantId)
            .conversationId(conversationId)
            .ownerUserId(ownerUserId)
            .beforeTurnNo(beforeTurnNo)
            .limit(limit)
            .build();
    List<ConsoleAiTurnEntity> turns = mapper.selectTurns(consoleAiConversationTurnsQuery);
    return turns.stream().map(this::toTurnView).toList();
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
    String conversationId = row.getConversationId();
    TurnView turn = toTurnView(row);
    return new ClientTurnView(conversationId, turn);
  }

  private TurnView toTurnView(ConsoleAiTurnEntity row) {
    long turnNo = row.getTurnNo();
    UUID clientTurnId = row.getClientTurnId();
    String contextVersion = row.getContextVersion();
    String prompt = decryptText(row.getPromptText());
    String response = decryptText(row.getResponseText());
    return new TurnView(
        turnNo,
        clientTurnId,
        contextVersion,
        prompt,
        response,
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
      String decryptedPrompt = decryptText(turn.getPromptText());
      turn.setPromptText(decryptedPrompt);
      String decryptedResponse = decryptText(turn.getResponseText());
      turn.setResponseText(decryptedResponse);
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
    Base64.Encoder encoder = Base64.getEncoder();
    byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = cryptoService.encrypt(plaintext, null);
    return encoder.encodeToString(ciphertext);
  }

  private String decryptText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    byte[] ciphertext = Base64.getDecoder().decode(value);
    byte[] plaintext = cryptoService.decrypt(ciphertext);
    return new String(plaintext, StandardCharsets.UTF_8);
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

  @Builder
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
