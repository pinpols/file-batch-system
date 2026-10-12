package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/** 批次日重放条目的状态与关联结果更新参数。id、目标状态和更新时间必填；其余字段为空时由 Mapper 保留原值。 */
@Getter
public final class UpdateBatchDayReplayEntryStatusParam {
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCEEDED = "SUCCEEDED";
  private static final String STATUS_FAILED = "FAILED";

  private final Long id;
  private final String toStatus;
  private final Long rerunInstanceId;
  private final Long resultVersionId;
  private final String failureReason;
  private final Instant startedAt;
  private final Instant finishedAt;
  private final Instant now;

  private UpdateBatchDayReplayEntryStatusParam(
      Long id, String toStatus, Instant now, EntryStatusDetails details) {
    this.id = Objects.requireNonNull(id, "id");
    this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
    EntryStatusDetails resolvedDetails = Objects.requireNonNull(details, "details");
    this.rerunInstanceId = resolvedDetails.rerunInstanceId();
    this.resultVersionId = resolvedDetails.resultVersionId();
    this.failureReason = resolvedDetails.failureReason();
    this.startedAt = resolvedDetails.startedAt();
    this.finishedAt = resolvedDetails.finishedAt();
    this.now = Objects.requireNonNull(now, "now");
  }

  public static UpdateBatchDayReplayEntryStatusParam running(
      Long id, Long rerunInstanceId, Instant startedAt, Instant now) {
    EntryStatusDetails details =
        new EntryStatusDetails(rerunInstanceId, null, null, startedAt, null);
    return new UpdateBatchDayReplayEntryStatusParam(id, STATUS_RUNNING, now, details);
  }

  public static UpdateBatchDayReplayEntryStatusParam succeededWithResult(
      Long id, Long resultVersionId, Instant completedAt) {
    EntryStatusDetails details =
        new EntryStatusDetails(null, resultVersionId, null, completedAt, completedAt);
    return new UpdateBatchDayReplayEntryStatusParam(id, STATUS_SUCCEEDED, completedAt, details);
  }

  public static UpdateBatchDayReplayEntryStatusParam failedWithResult(
      Long id, Long resultVersionId, String failureReason, Instant completedAt) {
    EntryStatusDetails details =
        new EntryStatusDetails(null, resultVersionId, failureReason, completedAt, completedAt);
    return new UpdateBatchDayReplayEntryStatusParam(id, STATUS_FAILED, completedAt, details);
  }

  public static UpdateBatchDayReplayEntryStatusParam failedOnSubmission(
      Long id, String failureReason, Instant failedAt) {
    EntryStatusDetails details =
        new EntryStatusDetails(null, null, failureReason, failedAt, failedAt);
    return new UpdateBatchDayReplayEntryStatusParam(id, STATUS_FAILED, failedAt, details);
  }

  public static UpdateBatchDayReplayEntryStatusParam completedRerun(
      Long id, String toStatus, Long rerunInstanceId, Instant finishedAt, Instant now) {
    EntryStatusDetails details =
        new EntryStatusDetails(rerunInstanceId, null, null, null, finishedAt);
    return new UpdateBatchDayReplayEntryStatusParam(id, toStatus, now, details);
  }

  private record EntryStatusDetails(
      Long rerunInstanceId,
      Long resultVersionId,
      String failureReason,
      Instant startedAt,
      Instant finishedAt) {}
}
