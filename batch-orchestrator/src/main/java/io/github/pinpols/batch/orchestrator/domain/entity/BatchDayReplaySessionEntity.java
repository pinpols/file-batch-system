package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * ADR-020 batch_day_replay_session 投影。
 *
 * <p>同 {@code (tenant_id, calendar_code, biz_date)} 内由 partial unique index保证至多 1 个 active session
 * （PENDING_APPROVAL / RUNNING）。
 */
@Builder(toBuilder = true)
public record BatchDayReplaySessionEntity(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String executionMode,
    String candidateSource,
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
    Instant updatedAt) {

  /** V202 前的兼容构造器：旧调用默认正式重放已有实例。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public BatchDayReplaySessionEntity(
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
      Instant updatedAt) {
    this(
        id,
        tenantId,
        calendarCode,
        bizDate,
        scope,
        "REPLAY",
        "EXISTING_INSTANCES",
        scopePayload,
        resultPolicy,
        configVersionPolicy,
        configVersion,
        reason,
        approvalId,
        status,
        totalCount,
        succeededCount,
        failedCount,
        inFlightCount,
        requestedBy,
        approvedBy,
        startedAt,
        completedAt,
        traceId,
        createdAt,
        updatedAt);
  }
}
