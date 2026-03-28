package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleWorkerRegistryResponse(
        Long id,
        String tenantId,
        String workerCode,
        String workerGroup,
        String capabilityTags,
        String resourceTag,
        String status,
        Instant heartbeatAt,
        Integer currentLoad,
        Instant drainStartedAt,
        Instant drainDeadlineAt
) {
}
