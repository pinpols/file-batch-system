package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/** ADR-020 batch_day_replay_entry 投影：session 内每个来源实例或 resultVersion 一行的重跑跟踪记录。 */
@Builder(toBuilder = true)
public record BatchDayReplayEntryEntity(
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

  /** V202 前的兼容构造器：历史 entry 没有计划快照。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public BatchDayReplayEntryEntity(
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
      Instant updatedAt) {
    this(
        id,
        sessionId,
        tenantId,
        jobCode,
        sourceInstanceId,
        rerunInstanceId,
        status,
        failureReason,
        startedAt,
        finishedAt,
        resultVersionId,
        null,
        createdAt,
        updatedAt);
  }
}
