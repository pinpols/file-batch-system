package io.github.pinpols.batch.console.domain.audit.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class ConsoleAiTurnEntity {

  private Long id;
  private String tenantId;
  private String conversationId;
  private Long turnNo;
  private UUID clientTurnId;
  private String contextVersion;
  private String promptText;
  private String responseText;
  private String turnStatus;
  private String promptDecision;
  private String modelName;
  private Integer promptTokens;
  private Integer completionTokens;
  private java.math.BigDecimal estimatedCostUsd;
  private Instant createdAt;
  private Instant completedAt;
}
