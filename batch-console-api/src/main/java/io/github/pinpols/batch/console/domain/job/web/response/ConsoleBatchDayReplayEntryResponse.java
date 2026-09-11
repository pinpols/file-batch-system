package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;

/** ADR-020 批次日重放 entry 响应（console 透传 orchestrator {@code BatchDayReplayEntryEntity} 的 JSON 投影）。 */
public record ConsoleBatchDayReplayEntryResponse(
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
    Instant createdAt,
    Instant updatedAt) {}
