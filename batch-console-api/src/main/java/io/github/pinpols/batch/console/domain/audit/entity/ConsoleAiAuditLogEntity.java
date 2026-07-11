package io.github.pinpols.batch.console.domain.audit.entity;

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

  /** 本次调用 prompt 消耗的 token 数(成本可观测；仅成功调用有值,拒绝/降级为 null)。 */
  private Integer promptTokens;

  /** 本次调用生成回复消耗的 token 数(成本可观测；仅成功调用有值,拒绝/降级为 null)。 */
  private Integer completionTokens;

  private Instant createdAt;
}
