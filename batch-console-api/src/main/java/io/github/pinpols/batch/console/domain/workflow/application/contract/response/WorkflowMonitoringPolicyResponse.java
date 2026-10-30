package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import java.time.Instant;
import java.time.LocalTime;
import lombok.Builder;

/** 根级软时限的对外视图；持久化载体不直接作为 Console API 契约。 */
@Builder
public record WorkflowMonitoringPolicyResponse(
    String tenantId,
    Long workflowDefinitionId,
    int softRuntimeSeconds,
    String softRuntimeSeverity,
    int startGraceSeconds,
    String startGraceSeverity,
    LocalTime completionDeadlineLocalTime,
    int completionDeadlineDayOffset,
    String completionDeadlineSeverity,
    Instant policyEffectiveAt,
    Instant updatedAt) {

  public static WorkflowMonitoringPolicyResponse from(WorkflowMonitoringPolicyEntity policy) {
    return WorkflowMonitoringPolicyResponse.builder()
        .tenantId(policy.getTenantId())
        .workflowDefinitionId(policy.getWorkflowDefinitionId())
        .softRuntimeSeconds(policy.getSoftRuntimeSeconds())
        .softRuntimeSeverity(policy.getSoftRuntimeSeverity())
        .startGraceSeconds(policy.getStartGraceSeconds())
        .startGraceSeverity(policy.getStartGraceSeverity())
        .completionDeadlineLocalTime(policy.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(policy.getCompletionDeadlineDayOffset())
        .completionDeadlineSeverity(policy.getCompletionDeadlineSeverity())
        .policyEffectiveAt(policy.getPolicyEffectiveAt())
        .updatedAt(policy.getUpdatedAt())
        .build();
  }
}
