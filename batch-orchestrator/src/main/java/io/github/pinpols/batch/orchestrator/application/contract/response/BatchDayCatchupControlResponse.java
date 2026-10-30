package io.github.pinpols.batch.orchestrator.application.contract.response;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import java.time.Instant;

/** 日历自动补扫控制状态的 HTTP 表示。 */
public record BatchDayCatchupControlResponse(
    String tenantId,
    String calendarCode,
    Boolean paused,
    Instant pausedAt,
    String pausedBy,
    String pauseReason,
    Instant updatedAt) {

  public static BatchDayCatchupControlResponse from(BatchDayCatchupControlEntity entity) {
    return new BatchDayCatchupControlResponse(
        entity.tenantId(),
        entity.calendarCode(),
        entity.paused(),
        entity.pausedAt(),
        entity.pausedBy(),
        entity.pauseReason(),
        entity.updatedAt());
  }
}
