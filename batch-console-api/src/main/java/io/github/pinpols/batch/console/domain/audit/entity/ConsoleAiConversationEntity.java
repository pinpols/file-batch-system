package io.github.pinpols.batch.console.domain.audit.entity;

import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class ConsoleAiConversationEntity {

  private String id;
  private String tenantId;
  private String ownerUserId;
  private String title;
  private String contextVersion;
  private Long nextTurnNo;
  private Instant createdAt;
  private Instant updatedAt;
  private OffsetDateTime expiresAt;
}
