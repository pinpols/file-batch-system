package io.github.pinpols.batch.console.domain.audit.application.ai;

import java.time.Instant;
import java.util.UUID;

/** 控制台 AI 图片附件应用端口。 */
public interface ConsoleAiAttachmentUseCase {

  boolean available();

  AttachmentView upload(String tenantId, String ownerUserId, UUID clientAttachmentId, byte[] input);

  AttachmentView status(String tenantId, String ownerUserId, UUID clientAttachmentId);

  ImageContent content(String tenantId, String ownerUserId, UUID id);

  void deleteDraft(String tenantId, String ownerUserId, UUID id);

  record AttachmentView(
      UUID id,
      UUID clientAttachmentId,
      String status,
      String mediaType,
      Long byteSize,
      Integer width,
      Integer height,
      Instant expiresAt) {}

  record ImageContent(byte[] bytes, String mediaType) {}
}
