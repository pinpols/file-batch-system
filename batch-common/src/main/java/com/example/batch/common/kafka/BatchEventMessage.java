package com.example.batch.common.kafka;

import java.time.Instant;
import java.util.Map;

public record BatchEventMessage(
        String schemaVersion,
        BatchMessageType messageType,
        String tenantId,
        String jobCode,
        String instanceNo,
        String partitionId,
        String taskId,
        String requestId,
        String traceId,
        String idempotencyKey,
        String businessKey,
        String producer,
        String eventName,
        String topic,
        String key,
        Instant eventTime,
        Map<String, Object> payload,
        Map<String, Object> ext
) {
}
