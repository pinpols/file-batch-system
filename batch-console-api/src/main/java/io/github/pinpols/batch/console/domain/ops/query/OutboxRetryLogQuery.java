package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Builder;

@Builder
public record OutboxRetryLogQuery(
    String tenantId,
    String retryStatus,
    String eventKey,
    String eventType,
    PageRequest pageRequest,
    Long cursorId) {}
