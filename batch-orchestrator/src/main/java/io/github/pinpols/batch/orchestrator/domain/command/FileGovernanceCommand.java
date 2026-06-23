package io.github.pinpols.batch.orchestrator.domain.command;

import lombok.Builder;

@Builder
public record FileGovernanceCommand(
    String tenantId,
    Long fileId,
    String channelCode,
    String operatorId,
    String traceId,
    String reason,
    String approvalId) {}
