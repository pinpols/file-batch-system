package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ConsoleAiAuditLogEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String traceId;
  private String sessionId;
  private String operatorId;
  private String promptCategory;
  private String promptDecision;
  private String modelName;
  private String promptHash;
  private String promptPreview;
  private String responseHash;
  private String responsePreview;
  private String refusalReason;
  private Instant createdAt;
}
