package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

/** 基于数据库当前尝试次数原子计算重试状态及退避的参数。 */
@Builder
public record TriggerLaunchRecoveryRetryParam(
    String tenantId,
    String requestId,
    Long recoveryGeneration,
    String errorCode,
    Instant now,
    int maxAttempts,
    long baseBackoffSeconds,
    long maxBackoffSeconds) {
  public TriggerLaunchRecoveryRetryParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(recoveryGeneration, "recoveryGeneration");
    Objects.requireNonNull(errorCode, "errorCode");
    Objects.requireNonNull(now, "now");
  }
}
