package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

/** 一次追平暂停状态写入，恢复时保留原暂停证据。 */
@Builder
public record BatchDayCatchupPauseStateParam(
    String tenantId,
    String calendarCode,
    boolean paused,
    Instant pausedAt,
    String pausedBy,
    String pauseReason,
    Instant updatedAt) {

  public BatchDayCatchupPauseStateParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(calendarCode, "calendarCode");
  }
}
