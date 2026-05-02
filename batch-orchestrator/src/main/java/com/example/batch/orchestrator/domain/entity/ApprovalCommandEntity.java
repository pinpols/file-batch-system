package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.relational.core.mapping.Column;

/**
 * {@code batch.approval_command} 表的 MyBatis ResultMap 数据载体。
 *
 * <p>本类<b>不是 Spring Data JDBC 实体</b>——审批命令属运行态状态机表（CLAUDE.md §架构硬约束），CRUD 全走 {@code
 * ApprovalCommandMapper.xml}（已存在）。{@code @Column} 注解对 MyBatis 无意义但保留以提升可读性， 不能加 {@code @Table} /
 * 不应被任何 {@code Repository<T,ID>} 引用。
 */
@Data
public class ApprovalCommandEntity {

  private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("approval_no")
  private String approvalNo;

  @Column("approval_type")
  private String approvalType;

  @Column("action_type")
  private String actionType;

  @Column("target_type")
  private String targetType;

  @Column("target_id")
  private String targetId;

  @Column("payload_json")
  private String payloadJson;

  @Column("approval_status")
  private String approvalStatus;

  @Column("requester_id")
  private String requesterId;

  @Column("approver_id")
  private String approverId;

  @Column("rejection_reason")
  private String rejectionReason;

  @Column("approval_reason")
  private String approvalReason;

  @Column("source_trace_id")
  private String sourceTraceId;

  @Column("source_idempotency_key")
  private String sourceIdempotencyKey;

  @Column("approved_at")
  private Instant approvedAt;

  @Column("executed_at")
  private Instant executedAt;
}
