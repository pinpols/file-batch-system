package com.example.batch.common.kafka;

import java.time.Instant;
import java.util.Map;

public record BatchRetryMessage(
    String schemaVersion,
    String tenantId,
    String jobCode,
    String instanceNo,
    String partitionId,
    String taskId,
    String retryKey,
    String idempotencyKey,
    String traceId,
    /** 消息投递尝试序号，用于重试调度，与数据库侧业务重试计数器不同。 */
    int attemptNo,
    int maxAttempts,
    Instant nextRetryAt,
    String retryReason,
    Map<String, Object> payload) {}
