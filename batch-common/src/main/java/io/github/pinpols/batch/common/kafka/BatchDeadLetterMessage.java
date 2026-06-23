package io.github.pinpols.batch.common.kafka;

import java.time.Instant;
import java.util.Map;

public record BatchDeadLetterMessage(
    String schemaVersion,
    String tenantId,
    String jobCode,
    String instanceNo,
    String partitionId,
    String taskId,
    String deadLetterKey,
    String idempotencyKey,
    String traceId,
    /** 记录进入死信队列时的消息投递尝试序号。 */
    int attemptNo,
    String deadReason,
    Instant deadAt,
    Map<String, Object> payload) {}
