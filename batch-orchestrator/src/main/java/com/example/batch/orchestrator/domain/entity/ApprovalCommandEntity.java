package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.approval_command")
public class ApprovalCommandEntity {

    @Id
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
