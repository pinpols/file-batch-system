package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record RetryScheduleQuery(
        String tenantId,
        String relatedType,
        String retryPolicy,
        String retryStatus,
        PageRequest pageRequest) {}
