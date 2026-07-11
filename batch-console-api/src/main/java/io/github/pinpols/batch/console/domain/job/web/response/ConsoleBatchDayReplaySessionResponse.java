package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * ADR-020 批次日重放 session 响应（console 透传 orchestrator {@code BatchDayReplaySessionEntity} 的 JSON 投影）。
 */
public record ConsoleBatchDayReplaySessionResponse(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String scopePayload,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String reason,
    Long approvalId,
    String status,
    Integer totalCount,
    Integer succeededCount,
    Integer failedCount,
    Integer inFlightCount,
    String requestedBy,
    String approvedBy,
    Instant startedAt,
    Instant completedAt,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {}
