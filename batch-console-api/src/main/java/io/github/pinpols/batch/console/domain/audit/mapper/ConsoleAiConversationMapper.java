package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiConversationEntity;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiTurnEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsoleAiConversationMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  ConsoleAiConversationEntity selectForUpdate(@Param("conversationId") String conversationId);

  int insertConversation(ConsoleAiConversationEntity conversation);

  Long allocateTurnNo(
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId,
      @Param("expiresAt") OffsetDateTime expiresAt,
      @Param("contextVersion") String contextVersion);

  List<ConsoleAiTurnEntity> selectRecentCompleteTurns(
      @Param("conversationId") String conversationId, @Param("limit") int limit);

  int insertTurn(ConsoleAiTurnEntity turn);

  int completeTurn(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("turnNo") long turnNo,
      @Param("responseText") String responseText,
      @Param("status") String status,
      @Param("promptDecision") String promptDecision,
      @Param("modelName") String modelName,
      @Param("promptTokens") Integer promptTokens,
      @Param("completionTokens") Integer completionTokens,
      @Param("estimatedCostUsd") java.math.BigDecimal estimatedCostUsd);

  List<ConsoleAiConversationEntity> selectByOwner(
      @Param("ownerUserId") String ownerUserId, @Param("limit") int limit);

  List<ConsoleAiTurnEntity> selectTurns(
      @Param("conversationId") String conversationId,
      @Param("ownerUserId") String ownerUserId,
      @Param("beforeTurnNo") Long beforeTurnNo,
      @Param("limit") int limit);

  int deleteConversation(
      @Param("conversationId") String conversationId, @Param("ownerUserId") String ownerUserId);

  List<String> selectActiveTenantIds();

  int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
