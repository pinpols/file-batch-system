package com.example.batch.console.repository;

public record WebhookDeliveryLogInsertParam(
    String tenantId,
    Long subscriptionId,
    String eventType,
    String payloadJson,
    Integer httpStatus,
    String responseBody,
    String deliveryStatus,
    int attempt) {}
