package io.github.pinpols.batch.console.shared.command;

import lombok.Builder;

/** 跨控制台上下文传递的审批提交载荷。 */
@Builder
public record ApprovalSubmitContext(
    String approvalType,
    String actionType,
    String targetType,
    String targetId,
    Object payload,
    String approvalReason,
    String idempotencyKey) {}
