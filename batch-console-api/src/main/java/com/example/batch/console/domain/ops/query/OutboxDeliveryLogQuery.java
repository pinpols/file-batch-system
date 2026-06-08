package com.example.batch.console.domain.ops.query;

import com.example.batch.common.model.PageRequest;

public record OutboxDeliveryLogQuery(
    String tenantId,
    String deliveryStatus,
    String eventType,
    String eventKey,
    String traceId,
    PageRequest pageRequest) {}
