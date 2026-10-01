package io.github.pinpols.batch.console.domain.audit.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class ConsoleAiAttachmentEntity {
  private String tenantId;
  private UUID id;
  private String ownerUserId;
  private UUID clientAttachmentId;
  private String inputSha256;
  private String objectKey;
  private String mediaType;
  private Long byteSize;
  private Integer width;
  private Integer height;
  private String status;
  private String conversationId;
  private Long turnNo;
  private Instant createdAt;
  private Instant expiresAt;
}
