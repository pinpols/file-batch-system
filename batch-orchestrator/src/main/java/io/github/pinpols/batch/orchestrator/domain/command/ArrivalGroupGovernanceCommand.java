package io.github.pinpols.batch.orchestrator.domain.command;

import lombok.Builder;

@Builder
public record ArrivalGroupGovernanceCommand(
    String tenantId,
    String fileGroupCode,
    String bizDate,
    String action,
    String operatorId,
    String traceId,
    String reason,
    Long extendWaitSeconds) {}
