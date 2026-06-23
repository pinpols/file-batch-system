package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record OutboxRetryLogQuery(
    String tenantId, String retryStatus, String eventKey, PageRequest pageRequest) {}
