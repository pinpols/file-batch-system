package com.example.batch.orchestrator.domain.query;

import java.time.Instant;

public record RetryScheduleQuery(
        String tenantId, String retryStatus, Instant dueBefore, Integer batchSize) {}
