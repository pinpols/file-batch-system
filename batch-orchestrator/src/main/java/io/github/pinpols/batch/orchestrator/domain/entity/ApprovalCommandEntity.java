package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.approval_command} 表的 MyBatis ResultMap 数据载体。
 *
 * <p>CRUD 全走 {@code ApprovalCommandMapper.xml}。
 */
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

  private Instant approvedAt;

  private Instant executedAt;
}
