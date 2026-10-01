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

  List<ConsoleAiTurnEntity> selectRecentCompleteTurns(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("limit") int limit);

  int insertTurn(ConsoleAiTurnEntity turn);

  ConsoleAiTurnEntity selectByClientTurnId(
      @Param("tenantId") String tenantId,
      @Param("ownerUserId") String ownerUserId,
      @Param("clientTurnId") UUID clientTurnId);

  int completeTurn(ConsoleAiTurnEntity turn);

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
