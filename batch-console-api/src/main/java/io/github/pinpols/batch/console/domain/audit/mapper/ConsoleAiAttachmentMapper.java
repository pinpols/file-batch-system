package io.github.pinpols.batch.console.domain.audit.mapper;

import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiAttachmentBindParam;
import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiAttachmentMarkDraftParam;


import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsoleAiAttachmentMapper {
  enum AttachmentStatus {
    UPLOADING,
    DRAFT,
    BOUND
  }

  record DraftTransitionParam(
      String tenantId,
      UUID id,
      String mediaType,
      long byteSize,
      int width,
      int height,
      String expectedStatus,
      String targetStatus) {}

  record BindTransitionParam(
      String tenantId,
      UUID id,
      String ownerUserId,
      String conversationId,
      long turnNo,
      Instant expiresAt,
      String expectedStatus,
      String targetStatus) {}

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

  default int markDraft(
      ConsoleAiAttachmentMarkDraftParam parameters) {
    String tenantId = parameters.tenantId();
    UUID id = parameters.id();
    String mediaType = parameters.mediaType();
    long byteSize = parameters.byteSize();
    int width = parameters.width();
    int height = parameters.height();

    DraftTransitionParam transition = new DraftTransitionParam(
        tenantId,
        id,
        mediaType,
        byteSize,
        width,
        height,
        AttachmentStatus.UPLOADING.name(),
        AttachmentStatus.DRAFT.name());
    return transitionToDraft(transition);
  }

  int transitionToDraft(DraftTransitionParam param);

  default int bind(
      ConsoleAiAttachmentBindParam parameters) {
    String tenantId = parameters.tenantId();
    UUID id = parameters.id();
    String ownerUserId = parameters.ownerUserId();
    String conversationId = parameters.conversationId();
    long turnNo = parameters.turnNo();
    Instant expiresAt = parameters.expiresAt();

    BindTransitionParam transition = new BindTransitionParam(
        tenantId,
        id,
        ownerUserId,
        conversationId,
        turnNo,
        expiresAt,
        AttachmentStatus.DRAFT.name(),
        AttachmentStatus.BOUND.name());
    return transitionToBound(transition);
  }

  int transitionToBound(BindTransitionParam param);

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
