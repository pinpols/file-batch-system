package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record OutboxDeliveryLogQuery(
    String tenantId,
    String deliveryStatus,
    String eventType,
    String eventKey,
    String traceId,
    PageRequest pageRequest) {}
