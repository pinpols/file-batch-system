package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleWorkerClaimedTaskResponse(
        Long id,
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        String taskType,
        Integer taskSeq,
        String taskStatus,
        String assignedWorkerCode,
        String taskPayload,
        String resultSummary,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {}
