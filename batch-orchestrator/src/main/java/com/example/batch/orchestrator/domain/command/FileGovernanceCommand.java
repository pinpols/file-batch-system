package com.example.batch.orchestrator.domain.command;

public record FileGovernanceCommand(
        String tenantId,
        Long fileId,
        String channelCode,
        String operatorId,
        String traceId,
        String reason
) {
}
