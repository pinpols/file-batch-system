package io.github.pinpols.batch.console.domain.ops.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;

@Builder
public record OutboxRetryLogQuery(
        String tenantId,
        String retryStatus,
        String eventKey,
        String eventType,
        PageRequest pageRequest,
        Long cursorId) {}
