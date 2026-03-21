package com.example.batch.orchestrator.domain.command;

public record TaskOutcomeCommand(
        String tenantId,
        Long taskId,
        boolean success,
        String errorCode,
        String errorMessage
) {
}
