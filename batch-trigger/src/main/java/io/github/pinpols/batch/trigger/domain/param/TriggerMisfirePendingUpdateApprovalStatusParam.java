package io.github.pinpols.batch.trigger.domain.param;

import lombok.Builder;

/** 绑定一次updateApprovalStatus操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record TriggerMisfirePendingUpdateApprovalStatusParam(
    Long id,
    String approvedBy,
    String rejectionReason,
    boolean writeRejectionReason,
    String targetStatus,
    String expectedStatus) {}
