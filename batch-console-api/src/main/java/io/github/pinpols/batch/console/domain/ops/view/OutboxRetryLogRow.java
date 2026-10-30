package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;

/**
 * event_outbox_retry 行投影（OutboxRetryLogMapper.selectByQuery，固定 11 列，snake_case 键）。
 *
 * <p>event_type / retry_policy 列在 SQL 中显式置 null（该表无此业务列），行类型按可空 String 承载。
 * next_retry_at 为 TIMESTAMPTZ 可空。
 */
public record OutboxRetryLogRow(
    Long id,
    Long outboxEventId,
    String tenantId,
    String eventType,
    String eventKey,
    String retryStatus,
    Integer retryCount,
    String retryPolicy,
    Instant nextRetryAt,
    Instant createdAt,
    Instant updatedAt) {}
