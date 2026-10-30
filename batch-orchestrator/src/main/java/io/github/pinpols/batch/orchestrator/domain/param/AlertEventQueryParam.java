package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;

/** 告警列表的租户与专项筛选条件。 */
@Builder
public record AlertEventQueryParam(
    String tenantId, String severity, String status, String alertType, Integer limit) {

  public AlertEventQueryParam {
    Objects.requireNonNull(tenantId, "tenantId");
  }
}
