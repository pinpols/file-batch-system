package io.github.pinpols.batch.console.domain.ops.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;

@Builder
public record OutboxDeliveryLogQuery(
        String tenantId,
        String deliveryStatus,
        String eventType,
        String eventKey,
        String targetTopic,
        String traceId,
        PageRequest pageRequest,
        Long cursorId) {}
