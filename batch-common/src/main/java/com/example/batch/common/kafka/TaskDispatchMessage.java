package com.example.batch.common.kafka;

import java.time.Instant;

public record TaskDispatchMessage(
        String schemaVersion,
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        Long taskId,
        String instanceNo,
        String jobCode,
        String taskType,
        Integer taskSeq,
        String workerType,
        String selectedWorkerId,
        String priorityBand,
        String businessKey,
        String payload,
        String traceId,
        String idempotencyKey,
        Instant dispatchAt
) {
}
