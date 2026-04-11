package com.example.batch.orchestrator.domain.query;

public record EventDeliveryLogQuery(
        String tenantId, String deliveryStatus, String eventType, String eventKey) {}
