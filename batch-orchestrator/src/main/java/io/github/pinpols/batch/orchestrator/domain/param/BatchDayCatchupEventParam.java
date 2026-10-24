package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;
import java.time.Instant;

/** 一次追平治理审计写入。 */
@Builder
public record BatchDayCatchupEventParam(
    String tenantId,
    String calendarCode,
    String action,
    String operatorId,
    String reason,
    Instant occurredAt) {

  public BatchDayCatchupEventParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(calendarCode, "calendarCode");
  }
}
