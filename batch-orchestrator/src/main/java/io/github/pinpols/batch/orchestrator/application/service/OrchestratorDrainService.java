package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 运维 Drain 用例入口，隔离 HTTP 适配层与运行时协调器。 */
@Service
@RequiredArgsConstructor
public class OrchestratorDrainService {

  private final OrchestratorGracefulShutdown gracefulShutdown;

  public DrainStatus status() {
    return toDrainStatus(gracefulShutdown.status());
  }

  public DrainStatus enable() {
    gracefulShutdown.startDraining("manual-enable");
    return status();
  }

  public DrainStatus disable() {
    gracefulShutdown.stopDraining("manual-disable");
    return status();
  }

  public boolean isDraining() {
    return gracefulShutdown.isDraining();
  }

  public void ensureAcceptingLaunches() {
    if (isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.orchestrator.draining");
    }
  }

  private static DrainStatus toDrainStatus(OrchestratorGracefulShutdown.DrainStatus status) {
    return new DrainStatus(status.draining(), status.drainingSince(), status.reason());
  }

  public record DrainStatus(boolean draining, Instant drainingSince, String reason) {}
}
