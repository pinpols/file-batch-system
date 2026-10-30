package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate.Violation;
import lombok.Builder;

/** 扫描与发送前复核共用的租户范围；资源及类型可进一步收窄到单项。 */
@Builder
public record WorkflowMonitoringQuery(
    String tenantId, String resourceKey, Violation violation, int limit) {}
