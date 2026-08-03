package io.github.pinpols.batch.console.shared.view;

import java.time.Instant;

/**
 * trace snapshot 的统一执行时间线项。
 *
 * <p>这是只读聚合视图，不改变各领域表的写入职责。source/eventType 使用固定枚举语义，供 console 按时间排序展示和运维排障。
 */
public record ConsoleTraceTimelineItem(
    String source,
    String eventType,
    Long referenceId,
    String status,
    String message,
    Instant occurredAt,
    String traceId) {}
