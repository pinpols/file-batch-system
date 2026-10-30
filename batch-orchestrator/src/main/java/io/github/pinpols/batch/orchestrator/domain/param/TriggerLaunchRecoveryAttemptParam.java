package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

/** 启动恢复推进一次尝试的参数（具名参数对象，避免 Mapper 长参数列表）。 */
@Builder
public record TriggerLaunchRecoveryAttemptParam(
    String tenantId,
    String requestId,
    Long recoveryGeneration,
    String status,
    Instant nextAttemptAt,
    String errorCode) {
  public TriggerLaunchRecoveryAttemptParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(recoveryGeneration, "recoveryGeneration");
    Objects.requireNonNull(status, "status");
  }
}
