package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import java.time.Instant;

/** 单条重放明细的 HTTP 契约。 */
public record BatchDayReplayEntryResponse(
    Long id,
    Long sessionId,
    String tenantId,
    String jobCode,
    Long sourceInstanceId,
    Long rerunInstanceId,
    String status,
    String failureReason,
    Instant startedAt,
    Instant finishedAt,
    Long resultVersionId,
    String planSnapshot,
    Instant createdAt,
    Instant updatedAt) {

  public static BatchDayReplayEntryResponse from(BatchDayReplayEntryEntity entity) {
    return new BatchDayReplayEntryResponse(
        entity.id(),
        entity.sessionId(),
        entity.tenantId(),
        entity.jobCode(),
        entity.sourceInstanceId(),
        entity.rerunInstanceId(),
        entity.status(),
        entity.failureReason(),
        entity.startedAt(),
        entity.finishedAt(),
        entity.resultVersionId(),
        entity.planSnapshot(),
        entity.createdAt(),
        entity.updatedAt());
  }
}
