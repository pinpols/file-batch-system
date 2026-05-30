package com.example.batch.console.domain.ops.query;

import com.example.batch.common.model.PageRequest;

public record OutboxRetryLogQuery(
    String tenantId, String retryStatus, String eventKey, PageRequest pageRequest) {}
