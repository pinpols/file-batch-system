package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsoleAiConversationMapper {

  enum TurnStatus {
    IN_PROGRESS,
    COMPLETE,
    FAILED
  }

  String setTenantContext(@Param("tenantId") String tenantId);

  ConsoleAiConversationEntity selectForUpdate(
      @Param("tenantId") String tenantId, @Param("conversationId") String conversationId);

  ConsoleAiConversationEntity selectActiveByOwner(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId);

  int insertConversation(ConsoleAiConversationEntity conversation);

  Long allocateTurnNo(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId,
      @Param("expiresAt") OffsetDateTime expiresAt,
      @Param("contextVersion") String contextVersion);

  default List<ConsoleAiTurnEntity> selectRecentCompleteTurns(
      String tenantId, String conversationId, int limit) {
    List<String> terminalStatuses = List.of(TurnStatus.COMPLETE.name(), TurnStatus.FAILED.name());
    return selectRecentTurnsByStatuses(tenantId, conversationId, limit, terminalStatuses);
  }

  List<ConsoleAiTurnEntity> selectRecentTurnsByStatuses(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("limit") int limit,
      @Param("turnStatuses") List<String> turnStatuses);

  default int insertTurn(ConsoleAiTurnEntity turn) {
    return insertTurnWithStatus(turn, TurnStatus.IN_PROGRESS.name());
  }

  int insertTurnWithStatus(
      @Param("turn") ConsoleAiTurnEntity turn, @Param("initialStatus") String initialStatus);

  ConsoleAiTurnEntity selectByClientTurnId(
      @Param("tenantId") String tenantId,
      @Param("ownerUserId") String ownerUserId,
      @Param("clientTurnId") UUID clientTurnId);

  default int completeTurn(ConsoleAiTurnEntity turn) {
    return completeTurnIfCurrent(turn, TurnStatus.IN_PROGRESS.name());
  }

  int completeTurnIfCurrent(
      @Param("turn") ConsoleAiTurnEntity turn, @Param("expectedStatus") String expectedStatus);

  List<ConsoleAiConversationEntity> selectByOwner(
      @Param("tenantId") String tenantId,
      @Param("ownerUserId") String ownerUserId,
      @Param("limit") int limit);

  List<ConsoleAiConversationEntity> selectPageByOwner(
      @Param("tenantId") String tenantId,
      @Param("ownerUserId") String ownerUserId,
      @Param("beforeUpdatedAt") Instant beforeUpdatedAt,
      @Param("beforeId") String beforeId,
      @Param("limit") int limit);

  List<ConsoleAiTurnEntity> selectTurns(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId,
      @Param("beforeTurnNo") Long beforeTurnNo,
      @Param("limit") int limit);

  int deleteConversation(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId);

  List<String> selectTenantIds();

  int deleteExpired(@Param("tenantId") String tenantId, @Param("cutoff") OffsetDateTime cutoff);
}
