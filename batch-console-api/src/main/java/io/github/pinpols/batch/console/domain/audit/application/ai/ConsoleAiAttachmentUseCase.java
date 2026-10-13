package io.github.pinpols.batch.console.domain.audit.application.ai;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
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

  record ImageContent(byte[] bytes, String mediaType) {
    public ImageContent {
      bytes = EmptyChecks.isNull(bytes) ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return EmptyChecks.isNull(bytes) ? null : bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other instanceof ImageContent(byte[] otherBytes, String otherMediaType)
          && Arrays.equals(bytes, otherBytes)
          && Objects.equals(mediaType, otherMediaType);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(bytes) + Objects.hashCode(mediaType);
    }

    @Override
    public String toString() {
      return "ImageContent[byteCount=" + (EmptyChecks.isNull(bytes) ? "null" : bytes.length)
          + ", mediaType="
          + mediaType + "]";
    }
  }
}
