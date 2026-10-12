package io.github.pinpols.batch.orchestrator.domain.param;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** 批次日重放会话状态 CAS 更新参数；必填字段在构造边界校验。 */
@Getter
public final class UpdateBatchDayReplaySessionStatusParam {
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_CANCELLED = "CANCELLED";

  private final String tenantId;
  private final Long id;
  private final String toStatus;
  private final List<String> expectedStatuses;
  private final Instant startedAt;
  private final Instant completedAt;
  private final String approvedBy;
  private final Instant now;

  private UpdateBatchDayReplaySessionStatusParam(
      String tenantId,
      Long id,
      String toStatus,
      List<String> expectedStatuses,
      Instant now,
      SessionStatusDetails details) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.id = Objects.requireNonNull(id, "id");
    this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
    this.expectedStatuses =
        List.copyOf(Objects.requireNonNull(expectedStatuses, "expectedStatuses"));
    if (EmptyChecks.isEmpty(this.expectedStatuses)) {
      throw new IllegalArgumentException("expectedStatuses must not be empty");
    }
    SessionStatusDetails resolvedDetails = Objects.requireNonNull(details, "details");
    this.startedAt = resolvedDetails.startedAt();
    this.completedAt = resolvedDetails.completedAt();
    this.approvedBy = resolvedDetails.approvedBy();
    this.now = Objects.requireNonNull(now, "now");
  }

  public static UpdateBatchDayReplaySessionStatusParam approved(
      String tenantId,
      Long id,
      List<String> expectedStatuses,
      String approvedBy,
      Instant approvedAt) {
    SessionStatusDetails details = new SessionStatusDetails(approvedAt, null, approvedBy);
    return new UpdateBatchDayReplaySessionStatusParam(
        tenantId, id, STATUS_RUNNING, expectedStatuses, approvedAt, details);
  }

  public static UpdateBatchDayReplaySessionStatusParam cancelled(
      String tenantId, Long id, List<String> expectedStatuses, Instant cancelledAt) {
    return completed(tenantId, id, STATUS_CANCELLED, expectedStatuses, cancelledAt);
  }

  public static UpdateBatchDayReplaySessionStatusParam completed(
      String tenantId,
      Long id,
      String toStatus,
      List<String> expectedStatuses,
      Instant completedAt) {
    SessionStatusDetails details = new SessionStatusDetails(null, completedAt, null);
    return new UpdateBatchDayReplaySessionStatusParam(
        tenantId, id, toStatus, expectedStatuses, completedAt, details);
  }

  private record SessionStatusDetails(Instant startedAt, Instant completedAt, String approvedBy) {}
}
