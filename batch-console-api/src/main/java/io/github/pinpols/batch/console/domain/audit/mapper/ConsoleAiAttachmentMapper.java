package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsoleAiAttachmentMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  int lockUploadQuota(@Param("tenantId") String tenantId);

  int activeDraftCount(
      @Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId);

  long activeDraftBytes(
      @Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId);

  long retainedBytesForUser(
      @Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId);

  long retainedBytesForTenant(@Param("tenantId") String tenantId);

  int insert(ConsoleAiAttachmentEntity attachment);

  ConsoleAiAttachmentEntity byClientId(
      @Param("tenantId") String tenantId,
      @Param("ownerUserId") String ownerUserId,
      @Param("clientAttachmentId") UUID clientAttachmentId);

  ConsoleAiAttachmentEntity byId(@Param("tenantId") String tenantId, @Param("id") UUID id);

  int markDraft(
      @Param("tenantId") String tenantId,
      @Param("id") UUID id,
      @Param("mediaType") String mediaType,
      @Param("byteSize") long byteSize,
      @Param("width") int width,
      @Param("height") int height);

  int bind(
      @Param("tenantId") String tenantId,
      @Param("id") UUID id,
      @Param("ownerUserId") String ownerUserId,
      @Param("conversationId") String conversationId,
      @Param("turnNo") long turnNo,
      @Param("expiresAt") Instant expiresAt);

  List<ConsoleAiAttachmentEntity> byTurn(
      @Param("tenantId") String tenantId,
      @Param("conversationId") String conversationId,
      @Param("turnNo") long turnNo);

  int enqueueForConversation(
      @Param("tenantId") String tenantId, @Param("conversationId") String conversationId);

  int enqueueExpiredConversations(@Param("tenantId") String tenantId, @Param("now") Instant now);

  int enqueueExpiredDrafts(@Param("tenantId") String tenantId, @Param("now") Instant now);

  int enqueueOne(@Param("tenantId") String tenantId, @Param("id") UUID id);

  int deleteOne(@Param("tenantId") String tenantId, @Param("id") UUID id);

  int deleteExpiredDrafts(@Param("tenantId") String tenantId, @Param("now") Instant now);

  List<String> cleanupKeys(@Param("tenantId") String tenantId, @Param("limit") int limit);

  int deleteCleanupKey(@Param("tenantId") String tenantId, @Param("key") String key);

  int incrementCleanupAttempts(@Param("tenantId") String tenantId, @Param("key") String key);
}
