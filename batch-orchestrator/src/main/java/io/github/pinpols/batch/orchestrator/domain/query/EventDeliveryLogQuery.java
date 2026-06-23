package io.github.pinpols.batch.orchestrator.domain.query;

public record EventDeliveryLogQuery(
    String tenantId, String deliveryStatus, String eventType, String eventKey) {}
