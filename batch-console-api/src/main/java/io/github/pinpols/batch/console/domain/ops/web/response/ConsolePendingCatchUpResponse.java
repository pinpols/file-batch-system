package io.github.pinpols.batch.console.domain.ops.web.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsolePendingCatchUpResponse(
    Long id,
    String tenantId,
    String requestId,
    String jobCode,
    LocalDate bizDate,
    String requestStatus,
    String traceId,
    Instant createdAt,
    Instant updatedAt,
    /**
     * 关联 approval_command.approval_no;走统一审批后端 {@code
     * /api/console/approvals/{approvalNo}/approve|reject} 操作。
     */
    String approvalNo,
    /** 关联 approval_command.approval_status: PENDING/APPROVED/REJECTED/EXECUTED, 可空。 */
    String approvalStatus) {}
