package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import java.time.Instant;
import lombok.Builder;

/** 单条重放明细的 HTTP 契约。 */
@Builder
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
    return BatchDayReplayEntryResponse.builder()
        .id(entity.id())
        .sessionId(entity.sessionId())
        .tenantId(entity.tenantId())
        .jobCode(entity.jobCode())
        .sourceInstanceId(entity.sourceInstanceId())
        .rerunInstanceId(entity.rerunInstanceId())
        .status(entity.status())
        .failureReason(entity.failureReason())
        .startedAt(entity.startedAt())
        .finishedAt(entity.finishedAt())
        .resultVersionId(entity.resultVersionId())
        .planSnapshot(entity.planSnapshot())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .build();
  }
}
