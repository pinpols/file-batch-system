package com.example.batch.console.domain.query;

public record OutboxDeliveryLogQuery(
        String tenantId,
        String deliveryStatus,
        String eventType,
        String eventKey
) {
}
