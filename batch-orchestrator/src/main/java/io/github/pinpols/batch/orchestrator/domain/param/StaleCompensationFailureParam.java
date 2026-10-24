package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;
import java.time.Instant;

/** 过期补偿失败收敛条件与错误证据。 */
@Builder
public record StaleCompensationFailureParam(
    String runningStatus,
    String failedStatus,
    Instant cutoff,
    String errorCode,
    String errorMessage,
    int limit) {

  public StaleCompensationFailureParam {
  }
}
