package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;

/**
 * outbox_event 按 publish_status 分组聚合投影（ConsoleOutboxEventReadMapper.statsByStatus，固定 4 列）。
 */
public record OutboxEventStatusStat(
    String publishStatus, long count, Instant oldest, Instant newest) {}
