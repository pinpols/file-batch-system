package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Builder;

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
