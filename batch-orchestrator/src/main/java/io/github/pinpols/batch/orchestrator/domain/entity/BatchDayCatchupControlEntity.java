package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;

/** 某租户日历当前由运维人员控制的自动补扫暂停状态。 */
public record BatchDayCatchupControlEntity(
    String tenantId,
    String calendarCode,
    Boolean paused,
    Instant pausedAt,
    String pausedBy,
    String pauseReason,
    Instant updatedAt) {}
