package io.github.pinpols.batch.console.domain.audit.application.contract.response;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

@Data
public class AiAuditLogResponse {

  private Long id;
  private String tenantId;
  private String requestId;
  private String traceId;
  private String sessionId;
  private String operatorId;
  private String promptCategory;
  private String promptDecision;
  private String modelName;
  private String promptPreview;
  private String responsePreview;
  private String refusalReason;
  private Integer promptTokens;
  private Integer completionTokens;
  private BigDecimal estimatedCostUsd;
  private String costStatus;
  private Instant createdAt;
}
