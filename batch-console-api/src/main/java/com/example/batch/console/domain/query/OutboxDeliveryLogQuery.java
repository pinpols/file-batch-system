package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record OutboxDeliveryLogQuery(
    String tenantId,
    String deliveryStatus,
    String eventType,
    String eventKey,
    PageRequest pageRequest) {}
