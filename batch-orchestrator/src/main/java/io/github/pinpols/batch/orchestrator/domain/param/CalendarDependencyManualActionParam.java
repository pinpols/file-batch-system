package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;
import java.time.Instant;

/** 一次等待记录的人工处置请求，状态竞争仍由原SQL控制。 */
@Builder
public record CalendarDependencyManualActionParam(
    String tenantId,
    Long id,
    String manualAction,
    String operatorId,
    String reason,
    Instant requestedAt) {

  public CalendarDependencyManualActionParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(id, "id");
  }
}
