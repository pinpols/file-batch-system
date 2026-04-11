package com.example.batch.orchestrator.domain.command;

public record ArrivalGroupGovernanceCommand(
        String tenantId,
        String fileGroupCode,
        String action,
        String operatorId,
        String traceId,
        String reason,
        Long extendWaitSeconds) {}
