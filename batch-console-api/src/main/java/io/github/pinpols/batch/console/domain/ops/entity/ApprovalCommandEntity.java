package io.github.pinpols.batch.console.domain.ops.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class ApprovalCommandEntity {

  private Long id;
  private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String targetType;
  private String targetId;
  private String payloadJson;
  private String approvalStatus;
  private String requesterId;
  private String approverId;
  private String rejectionReason;
  private String approvalReason;
  private String sourceTraceId;
  private String sourceIdempotencyKey;
  private OffsetDateTime approvedAt;
  private OffsetDateTime executedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
